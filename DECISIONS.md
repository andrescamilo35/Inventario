# Decisiones

## Estructura

| Paquete / clase | Responsabilidad |
| --- | --- |
| `api` | Contrato. No se modifica. |
| `Inventory` | Punto de entrada; construye el servicio con las reglas por defecto. |
| `policy.CategoryPolicy` | Reglas de una categoría: tiempo para pagar y límite opcional por pedido. |
| `policy.CategoryPolicies` | **Único lugar** donde se definen las reglas de cada categoría. |
| `service.InMemoryInventoryService` | Orquesta las operaciones, la concurrencia y los avisos. |
| `service.Product` | Stock, unidades reservadas y estado del aviso de un SKU. |

### Añadir una categoría nueva (cada temporada)

1. Añadir la constante a `ProductCategory` (lo hace el dueño del contrato).
2. Añadir su regla en `CategoryPolicies.defaults()`.

Si se olvida el paso 2, `CategoryPolicies` lanza una excepción al arrancar y el test
`defaultsDefineRulesForEveryCategory` falla indicando qué categoría falta. Ninguna otra clase
conoce categorías concretas.

## Supuestos

### Reservas y expiración
- La reserva dura según la categoría del producto **en el momento de reservar**. Si luego cambia la
  categoría, las reservas existentes conservan su vencimiento.
- Una reserva vence exactamente al cumplirse su plazo (`now >= expiresAt`).
- Las reservas vencidas se liberan de forma perezosa, al tocar su producto o su pedido. No hace falta
  un proceso en segundo plano: `available` nunca cuenta una reserva vencida.

### Reintentos de la app (idempotencia por `orderId`)
- El mismo `orderId` con el mismo SKU y cantidad devuelve la reserva original (mismo `expiresAt`),
  aunque ya no queden unidades libres.
- El mismo `orderId` con otro SKU o cantidad lanza `IllegalArgumentException`.
- Un pedido **ya pagado** también se recuerda: un reintento tardío de `reserve` devuelve la reserva
  confirmada en vez de apartar unidades otra vez.
- Un pedido **vencido** sin pagar libera su `orderId`, que puede volver a reservar.
- `confirm` repetido lanza `IllegalStateException`, como exige el contrato. El cliente debe tratarlo
  como "ya confirmado" si ya recibió un éxito previo.

### Límite por pedido
- Se comprueba antes que el stock: pedir 3 unidades de un `FLASH_SALE` sin stock informa del límite.
- Es por pedido, no por cliente (el contrato no conoce al cliente).

### Avisos de stock bajo
- Se avisa cuando las unidades disponibles (stock − reservas activas) quedan en 5 o menos, con el
  número real de unidades.
- No se repite hasta que un **reabastecimiento** (`addStock`) deja el producto por encima de 5.
  Que una reserva venza no cuenta como reabastecimiento, siguiendo el enunciado literalmente.
- El aviso se envía **después de liberar el bloqueo**: un canal lento no frena el inventario.
- Si el canal falla, se registra el error y la operación termina bien. La alternativa (propagar el
  error) haría que el cliente reintentara y, por ejemplo, sumara el stock dos veces. El aviso fallido
  se pierde; ver "Antes de producción".

### Registro y validaciones
- Registrar un SKU existente **no borra** su stock ni sus reservas; solo actualiza la categoría.
- `sku`, `orderId` y `category` vacíos o nulos lanzan `IllegalArgumentException`.
- Cantidades `<= 0` y desbordamiento de stock lanzan `IllegalArgumentException`.
- Reservar un producto desconocido lanza `InsufficientStockException` con 0 disponibles.

## Concurrencia

Todas las operaciones usan un único bloqueo por instancia. Así "comprobar stock y reservar" es
atómico y no hay sobreventa. Es suficientemente rápido en memoria: cada operación es O(log n) sobre
las reservas de un producto (reservas ordenadas por vencimiento e índice global por `orderId`).

`ConcurrencyTest` lanza cientos de pedidos simultáneos y verifica que no hay sobreventa, que los
reintentos concurrentes reservan una sola vez, que una confirmación concurrente vende una sola vez,
que el aviso sale una sola vez y que un canal de avisos lento no bloquea otras operaciones. Se
comprobó que estos tests fallan si se quita el bloqueo.

## Fuera de alcance

- Persistencia y ejecución en varias instancias (el estado vive en una JVM).
- Limpieza de los pedidos confirmados que se recuerdan para idempotencia (crecen sin límite).
- Reintento de avisos fallidos y canales distintos del `StockAlertListener` recibido.
- Cancelación explícita de reservas, API HTTP, autenticación.

## Antes de producción

1. **Base de datos y varias instancias.** El bloqueo en memoria deja de servir. Extraer un puerto de
   repositorio y reservar con una operación atómica en la base, por ejemplo
   `UPDATE stock SET reserved = reserved + :q WHERE sku = :sku AND total - reserved >= :q`
   (o bloqueo optimista con versión), dentro de la misma transacción que inserta la reserva.
2. **Idempotencia** con restricción `UNIQUE(order_id)` y retención limitada de pedidos confirmados.
3. **Avisos** con patrón outbox: guardar el aviso en la misma transacción y publicarlo de forma
   asíncrona con reintentos. El estado "ya avisado" debe vivir en la base, no en memoria.
   Para más canales, un `StockAlertListener` compuesto que reparta a correo, Slack, etc.
4. **Vencimientos**: un job que libere reservas vencidas en lote, además de la evaluación perezosa,
   para que los informes de stock sean exactos.
5. **Reglas por categoría** configurables (fichero o base) si Marketing necesita cambiarlas sin
   desplegar.
6. Métricas (reservas, vencimientos, rechazos por stock), logs estructurados y trazas.
