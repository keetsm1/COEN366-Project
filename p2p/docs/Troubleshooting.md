# Troubleshooting

## No TCP listener on storage peer
- Symptom: Owner cannot connect; `Connection refused` or hangs.
- Check: Storage terminal shows `Listening TCP on <peer.tcpPort>`.
- Fix: Start storage with `./run_peer.ps1 heartbeat -Config config.bob.properties`. Ensure firewall allows the port.

## No CHUNK_OK acknowledgment
- Symptom: Owner retries and eventually cancels chunk.
- Check: Storage logs for `SEND_CHUNK` header and CRC status.
- Fixes:
  - Verify storage UDP port in header (`OwnerUdpPort`) is correct.
  - Confirm `peer.udpPort` is reachable and not blocked by firewall.
  - Validate `backup.chunkSize` matches the sender’s configuration when using offline mode.

## ACK timeout
- Symptom: Owner reports `Timeout waiting for CHUNK_OK`.
- Facts: Owner waits 5 seconds and retries up to 3 attempts.
- Fix: Reduce network latency, ensure storage is sending `CHUNK_OK`, and confirm UDP delivery.

## Server unreachable
- Symptom: No `BACKUP_PLAN` received.
- Fix: Ensure server host/port are correct; otherwise rely on `backup.staticPeers` and `backup.chunkSize` for offline backup.

## CRC validation fails
- Symptom: Storage sends `CHUNK_ERROR`; chunk not saved.
- Check: File integrity and transmitted bytes count.
- Fix: Verify chunk slicing and header fields; re-run with a fresh build.

## Port conflicts
- Symptom: Bind/Listen errors on UDP/TCP ports.
- Fix: Change ports in config; stop processes occupying the ports; re-run.

## Clearing runtime data
- `storage/<file>/` holds chunks; delete to reset stored state.
- `out/` holds compiled classes; delete and re-run `./build.ps1` to regenerate.