# Operations Guide

## Build
- Ensure Java is installed and available on PATH.
- Run: `./build.ps1`
- Output classes are placed under `out/` and used by the runner.

## Run Modes
- Storage peer: `./run_peer.ps1 heartbeat -Config config.bob.properties`
  - Starts UDP heartbeat loop and TCP storage listener on `peer.tcpPort`.
- Owner backup: `./run_peer.ps1 backup <file> -Config config.alice.properties`
  - Sends `BACKUP_REQ` then chunks the file, sends over TCP, waits for acks, and emits `BACKUP_DONE`.

## Local Demo (Two Peers)
1) Build the project.
2) Terminal A (Bob storage):
   - `./run_peer.ps1 heartbeat -Config config.bob.properties`
   - Observe: `HEARTBEAT` logs every `heartbeat.intervalSeconds`, and TCP listening on `peer.tcpPort`.
3) Terminal B (Alice owner):
   - `./run_peer.ps1 backup sample.txt -Config config.alice.properties`
   - Observe: `BACKUP_REQ`, then `SEND_CHUNK` header over TCP, and `CHUNK_OK`/`BACKUP_DONE`.

## Verification
- On-disk artifacts:
  - `storage/<file>/chunk-0.bin` exists for small files.
  - For multi-chunk files, sequential `chunk-<id>.bin` files appear.
- Content/size/hash (PowerShell):
  - `Get-Content sample.txt -TotalCount 3`
  - `(Get-Item sample.txt).Length`, `(Get-Item storage/sample.txt/chunk-0.bin).Length`
  - `Get-FileHash sample.txt -Algorithm MD5`
  - `Get-FileHash storage/sample.txt/chunk-0.bin -Algorithm MD5`
  - Hashes should match when the whole file fits in one chunk.

## Multi-Chunk Example
- To demonstrate multiple `CHUNK_OK` events, reduce `backup.chunkSize` (e.g., `1024`).
- Re-run owner backup; expect `chunk-0.bin`, `chunk-1.bin`, ... and matching CRCs per chunk.

## Cleanup
- You can safely delete `storage/<file>/` directories to clear stored data.
- `out/` is build output; delete and re-run `./build.ps1` to regenerate.