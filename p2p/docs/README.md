# P2PBRS — README

## Major Goal
- Back up files peer-to-peer while sending regular heartbeats to a coordination server.

## Prerequisites
- Windows PowerShell, Java on PATH.
- Two terminals for a local demo: one for storage (Bob), one for owner (Alice).

## Step-by-Step: Build, Run, Verify
- Build classes
  - `./build.ps1`
  - If a later run complains “Build output 'out' not found”, re-run this.

- Start storage peer (Bob)
  - `./run_peer.ps1 heartbeat -Config config.bob.properties`
  - Expected logs:
    - `REGISTER ... Bob STORAGE ...`
    - `HEARTBEAT ... Bob ...`
    - `STORAGE TCP receiver listening on port 6002`
  - Optional port check (faster than Test-NetConnection):
    - `netstat -ano | findstr LISTENING | findstr :6002`

- Prepare a test file (if `sample.txt` was deleted)---run this in PowerShell:
  $lines = @('Hello from P2PBRS','this is just a sample for testing','Line 3: OK'); Set-Content -Path 'sample.txt' -Value $lines -Encoding ASCII; Get-Item 'sample.txt' | Format-Table Name,Length,FullName -AutoSize 
- Run owner backup (Alice)
  - `./run_peer.ps1 backup sample.txt -Config config.alice.properties`
  - Expected logs:
    - `REGISTER ... Alice OWNER ...`
    - `BACKUP_REQ ... sample.txt ...`
    - `BACKUP_PLAN using local static peers, chunkSize=65536`
    - `SEND_CHUNK ... file=sample.txt id=0 size=<file bytes> -> 127.0.0.1:6002 (attempt 1)`
    - `CHUNK_OK ... file=sample.txt id=0`
    - `BACKUP_DONE ... sample.txt`

- Verify artifacts
  - On disk: `storage/sample.txt/chunk-0.bin` (matches your file size when single-chunk).
  - Hash match (for single-chunk files):
    - `Get-FileHash sample.txt -Algorithm MD5`
    - `Get-FileHash storage\sample.txt\chunk-0.bin -Algorithm MD5`
    - Hashes should be identical for identical content.

## Multi-Chunk Demo
- To see multiple `CHUNK_OK` events, set `backup.chunkSize=1024` in `config.alice.properties` and re-run the backup.
- Expect `chunk-0.bin`, `chunk-1.bin`, ... in `storage/sample.txt/`.

## Common Pitfalls and Fixes
- Build output missing
  - Symptom: `Build output 'out' not found.`
  - Fix: Run `./build.ps1` before any `run_peer.ps1` commands.

- Storage not listening / connection refused
  - Ensure Bob is running: `./run_peer.ps1 heartbeat -Config config.bob.properties`.
  - Check port: `netstat -ano | findstr LISTENING | findstr :6002`.
  - Close any process occupying `6002` or change `peer.tcpPort` in `config.bob.properties`.

- Test file missing
  - Symptom: `BACKUP failed: sample.txt (The system cannot find the file specified)`.
  - Fix: recreate `sample.txt` (see command above) or point to a real file.

- Test-NetConnection appears stuck
  - Use `netstat -ano | findstr LISTENING | findstr :6002` for quick confirmation.

## Configuration Reference
- See `docs/Configuration.md` for all keys and example configs.
- Key items (commonly used in local demo):
  - `backup.staticPeers=[Bob@127.0.0.1:6002:5002]`
  - `backup.chunkSize=65536`
  - Alice: `peer.udpPort=5001`, `peer.tcpPort=6001`
  - Bob: `peer.udpPort=5002`, `peer.tcpPort=6002`

## Navigation
- `Overview.md` — Architecture and flows.
- `Configuration.md` — Keys, formats, examples.
- `Protocol.md` — Message formats: `REGISTER`, `HEARTBEAT`, `BACKUP_REQ`, `SEND_CHUNK`, `CHUNK_OK`, `BACKUP_DONE`.
- `Operations.md` — Build/run demo, verification, multi-chunk example.
- `Troubleshooting.md` — Diagnostics for networking, acks, CRC, and config.