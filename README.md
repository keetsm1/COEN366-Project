# COEN366 – Distributed Backup & Restore System

This project implements a small distributed backup system where a central server coordinates multiple peers. Each peer can register with the server, back up files to other peers, and later restore those files using both UDP and TCP communication.

The system was completed in three phases as part of the COEN366 course.



## Overview
The system is made up of:

- **A central Server** (UDP): keeps track of peers and coordinates backup/restore plans.
- **Peers** (UDP + TCP): they register with the server and either store files or request backups/restores.

Peers can take on different roles:
- **OWNER** – sends files for backup  
- **STORAGE** – stores chunks  
- **BOTH** – can do both roles (this is how we tested most features)



## Phase 1 – Registration / Deregistration (UDP)
The first phase handles the basic communication:

- Peers send a `REGISTER` message to the server.
- The server records information such as peer name, IP, UDP port, TCP port, and capacity.
- Peers can deregister using `DE-REGISTER`.
- Using the `list` command, peers can retrieve the list of all active peers.

This ensures the server always knows which peers are available.



## Phase 2 – File Backup (UDP + TCP)
Phase 2 adds file backup functionality:

1. A peer sends a `BACKUP_REQ` to the server.
2. The server selects a storage peer and replies with a `BACKUP_PLAN` that includes:
   - File name
   - Storage peer list
   - Chunk size
3. The owner peer connects to the selected storage peer via TCP and sends the file chunk using:

   
   SEND_CHUNK rq fileName chunkId chunkSize checksum
   

4. The storage peer stores the chunk in the `storage/` folder and sends back:
   - `CHUNK_OK` (success)
   - and later a `STORE_ACK` to the server.

5. After the chunk is successfully sent, the owner peer sends `BACKUP_DONE`.



## Phase 3 – Restore & Integrity Validation (TCP)
The final phase handles file recovery:

1. The owner peer sends `RESTORE_REQ` to the server.
2. The server checks where the file is saved and replies with a `RESTORE_PLAN`.
3. The owner peer connects to the correct storage peer via TCP and sends:

   
   GET_CHUNK rq fileName chunkId
   

4. The storage peer responds with:

   
   CHUNK_DATA rq fileName chunkId chunkSize checksum

   
   followed by the raw binary data.

5. The owner peer rebuilds the file inside the `restored/` folder and verifies the checksum.

6. If the checksum matches, the peer sends `RESTORE_OK` to the server; otherwise, it sends `RESTORE_FAIL`.



## Project Structure

src/
 ├── server/
 │    └── Server.java
 ├── peer/
 │    ├── PeerUDP.java
 │    └── PeerData.java

storage/        # Stored chunks
restored/       # Restored files


## How to Compile
javac -d out src/server/*.java src/peer/*.java

## Start the Server
java -cp out server.Server

## Start a Peer (each peer must run in its own terminal)
java -cp out peer.PeerUDP

## Peer Commands


list
de
backup <filename>
restore <filename>


Restored files are placed in the `restored/` directory.

## Example Output

### Backup

BACKUP_PLAN 02 test.txt [PeerB]
CHUNK_OK
BACKUP_DONE


### Restore

RESTORE_PLAN 05 test.txt [PeerB]
Restore checksum: true
File restored to /restored/

## What Works

- Peer registration and listing  
- Backup to remote peer using TCP  
- Chunk storage with checksum verification  
- Restore using GET_CHUNK and CHUNK_DATA  
- Full end-to-end flow for all three phases  
