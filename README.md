# NiFi MQTT Support

## Currently implemented:
- MQTT Subscribe

## Not Implemented Yet:
- `PutMQTT` (Doh, but I want to get `GetMQTT` solid, after which it will be easy to refactor common code) 
- No action taken on the client side to guarantee QoS 2, limited support
- Only TCP support, no SSL or WebSockets
- Last Will Topics
 
## Notes:
- The `GetMQTT` processor will not allow raising its concurrency beyond 1. This was an *explicit design choice*.
  If you were pulling your hair trying to change it in the UI, don't worry, it's not you! Filed a https://issues.apache.org/jira/browse/NIFI-1427 .
  It is recommended one creates additional `GetMQTT` instances on the canvas with more specific subscription filters if 
  needed, throughput is not an issue.
- For Last Will Topics the payload must be a String, no binary support. This makes sense, as we are configuring things via a UI.