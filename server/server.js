'use strict';

const express   = require('express');
const http      = require('http');
const WebSocket = require('ws');

const app    = express();
const server = http.createServer(app);
const wss    = new WebSocket.Server({ server });

// rooms → Map<roomId, Set<{ws, deviceId}>>
const rooms = new Map();

function getRoomClients(roomId) {
  if (!rooms.has(roomId)) rooms.set(roomId, new Set());
  return rooms.get(roomId);
}

function cleanRoom(roomId) {
  const clients = rooms.get(roomId);
  if (clients && clients.size === 0) rooms.delete(roomId);
}

wss.on('connection', (ws, req) => {
  const ip = req.socket.remoteAddress;
  console.log(`[+] Connected: ${ip}`);

  let roomId   = null;
  let deviceId = null;

  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); }
    catch (e) { console.warn('[!] Bad JSON from', deviceId); return; }

    if (msg.type === 'join') {
      roomId   = String(msg.room_id  || '').trim();
      deviceId = String(msg.device_id || '').trim();

      if (!roomId || !deviceId) {
        ws.send(JSON.stringify({ type: 'error', message: 'Missing room_id or device_id' }));
        return;
      }

      const clients = getRoomClients(roomId);
      clients.add({ ws, deviceId });
      console.log(`[JOIN] device=${deviceId}  room=${roomId}  peers=${clients.size}`);
      ws.send(JSON.stringify({ type: 'joined', room_id: roomId, peers: clients.size }));
      return;
    }

    if (msg.type === 'clipboard') {
      if (!roomId) { console.warn('[!] clipboard msg before join'); return; }

      const dataType = msg.data_type || 'text';
      const size     = dataType === 'text'
        ? `${(msg.content || '').length} chars`
        : `${Math.round((msg.content || '').length * 0.75 / 1024)} KB`;

      console.log(`[SYNC] device=${msg.device_id}  room=${roomId}  type=${dataType}  size=${size}`);

      const clients = getRoomClients(roomId);
      for (const peer of clients) {
        if (peer.ws !== ws && peer.ws.readyState === WebSocket.OPEN) {
          peer.ws.send(raw.toString());
        }
      }
      return;
    }

    console.warn('[?] Unknown message type:', msg.type);
  });

  ws.on('close', () => {
    if (roomId && rooms.has(roomId)) {
      const clients = rooms.get(roomId);
      for (const peer of clients) {
        if (peer.ws === ws) { clients.delete(peer); break; }
      }
      cleanRoom(roomId);
    }
    console.log(`[-] Disconnected: device=${deviceId || 'unknown'}  ip=${ip}`);
  });

  ws.on('error', (err) => console.error(`[ERR] ${deviceId || ip}: ${err.message}`));
});

// Heartbeat – drop dead connections every 30 s
const heartbeat = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 30_000);

wss.on('close', () => clearInterval(heartbeat));

app.get('/health', (_req, res) => {
  res.json({ status: 'ok', rooms: rooms.size, clients: wss.clients.size });
});

app.get('/rooms', (_req, res) => {
  const info = {};
  for (const [id, clients] of rooms) info[id] = clients.size;
  res.json(info);
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`\n[ClipSync] Server running on port ${PORT}\n`);
});
