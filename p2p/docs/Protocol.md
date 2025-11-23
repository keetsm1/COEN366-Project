# Protocol Reference

## Control Messages (UDP)

- REGISTER
  - `REGISTER RQ# Name Role IP UDP_Port TCP_Port StorageMB`
  - Purpose: register peer with the coordination server.

- REGISTERED
  - `REGISTERED Name OK`
  - Purpose: server acknowledgment of registration.

- HEARTBEAT
  - `HEARTBEAT Name ChunkCount Timestamp`
  - Purpose: periodic liveness and status.

- BACKUP_REQ
  - `BACKUP_REQ Name FileName FileSize CRC32`
  - Purpose: owner requests a plan for backing up a file.

- BACKUP_PLAN
  - `BACKUP_PLAN Name FileName ChunkSize PeerList`
  - Purpose: server provides chunk size and peer endpoints.
  - If absent, the owner uses `backup.staticPeers` and `backup.chunkSize` locally.

- BACKUP_DENIED
  - `BACKUP_DENIED Name Reason`
  - Purpose: server denies a plan; owner may fall back to static peers.

- CHUNK_OK
  - `CHUNK_OK OwnerName FileName ChunkId`
  - Purpose: storage confirms chunk written and CRC verified.

- CHUNK_ERROR
  - `CHUNK_ERROR OwnerName FileName ChunkId Reason`
  - Purpose: storage reports failure; owner may retry.

- BACKUP_DONE
  - `BACKUP_DONE Name FileName TotalChunks`
  - Purpose: owner indicates successful completion (best-effort notification).

## Data Transfer (TCP)

- SEND_CHUNK header
  - `SEND_CHUNK OwnerName OwnerUdpPort FileName FileSize FullCRC ChunkId ChunkOffset ChunkSize ChunkCRC`
  - Purpose: precedes raw chunk bytes; gives storage enough context to validate and respond.
  - After the header, exactly `ChunkSize` bytes follow.

## Acknowledgment Flow
- Owner registers an ack waiter per chunk and sends header+bytes over TCP.
- Storage validates CRC, persists to `storage/<FileName>/chunk-<id>.bin`, and sends `CHUNK_OK`.
- Owner waits up to 5 seconds for ack; on timeout or `CHUNK_ERROR` retries up to 3 attempts.
- After all chunks succeed, owner sends `BACKUP_DONE`.