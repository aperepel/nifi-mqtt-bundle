# NiFi MQTT Support

## Currently implemented:
- MQTT Subscribe

## Not Implemented Yet:
- `PutMQTT` (Doh, but I want to get `GetMQTT` solid, after which it will be easy to refactor common code) 
- No action taken on the client side to guarantee QoS 2, limited support
- Only TCP support, no SSL or WebSockets
- ~~Last Will Topics~~ Done.
 
## Notes:
- The `GetMQTT` processor will not allow raising its concurrency beyond 1. This was an *explicit design choice*.
  Concurrent receivers don't mix well with topic semantics and unique clientIds
  If you were pulling your hair trying to change it in the UI, don't worry, it's not you! Filed a https://issues.apache.org/jira/browse/NIFI-1427 .
  It is recommended one creates additional `GetMQTT` instances on the canvas with more specific subscription filters if 
  needed, throughput is not an issue.
- For Last Will Topics the payload must be a String, no binary support. This makes sense, as we are configuring things via a UI.
- Due to the nature of MQTT spec and underlying library implementation, NiFi's backpressure counts are not guaranteed to be precise when changing
  the `Receive Buffer Count`. The reason is an inherent message backlog on the network. Changing this setting from a default of `1` will yield
  a much higher throughput, but after `GetMQTT` is stoped one might end up with a queue size of ~2x the backpressure limit. It won't go above that on
  component restart. The processor will go to lengths in order to flush the incoming messages from the memory buffer and network into NiFi, thus avoiding
  any message loss or having to implement more complex persistence strategies. **Above does not apply to a default setting of `1`**, which
  is plenty fast and has no surprises for a NiFi user.
