# 📦 COEN366 – Distributed Backup & Restore System

This project implements a **distributed file backup and restore system** using **UDP and TCP**.  
Built in three phases, it allows peers to register, back up files to other peers, and restore those files later with checksum validation.

The system models a simplified distributed storage service with:

- A **central server** (UDP only)
- Several **peers** (UDP + TCP)

Each peer can act as:
- **OWNER**  
- **STORAGE**  
- **BOTH**  

---

# 🚀 Features Overview

## **Phase 1 — Registration / Deregistration (UDP)**  
✔ Peers register with the server  
✔ Peers can deregister  
✔ Server tracks active peers  
✔ “LIST” command shows all peers  

---

## **Phase 2 — Backup & Chunk Storage (UDP + TCP)**

- Owner peer sends `BACKUP_REQ`  
- Server replies with `BACKUP_PLAN`  
- Owner connects to storage peer via TCP and sends:  
```
SEND_CHUNK rq fileName chunkId chunkSize checksum
```
- Storage peer validates, stores, and sends `CHUNK_OK`  
- Server updates metadata with storage location  

---

## **Phase 3 — Restore & Integrity Validation (TCP)**

- Owner sends `RESTORE_REQ`  
- Server sends `RESTORE_PLAN`  
- Owner connects to storage peer and sends:
```
GET_CHUNK rq fileName chunkId
```
- Storage peer responds:
```
CHUNK_DATA rq fileName chunkId chunkSize checksum
```
- Owner reassembles file under `restored/`  
- CRC32 checksum verified  
- Sends `RESTORE_OK` or `RESTORE_FAIL`

All restore logic is complete and functional.

---

# 🏗 Project Structure

```
src/
├── server/
│   └── Server.java
├── peer/
│   ├── PeerUDP.java
│   └── PeerData.java

storage/       # stored chunks
restored/      # restored files
```

---

# 🖥️ How to Compile & Run

## **1. Compile**
```
javac -d out src/server/*.java src/peer/*.java
```

## **2. Start Server**
```
java -cp out server.Server
```

## **3. Start Peers**
```
java -cp out peer.PeerUDP
```

Register each peer with a name, role, UDP port, and storage capacity.

---

# 📝 Commands

```
list
de
backup <filename>
restore <filename>
```

---

# 🔍 Sample Output

### Backup
```
BACKUP_PLAN 02 test.txt [PeerB] 4096
CHUNK_OK
BACKUP_DONE
```

### Restore
```
RESTORE_PLAN 05 test.txt [PeerB]
Restore checksum: true
File restored to /restored/
```

---

# 🧪 Checklist
- [x] Registration  
- [x] Backup  
- [x] Storage  
- [x] Restore  
- [x] Checksum validation  

---
