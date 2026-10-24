# Pôr o relay no servidor da Contabo

O servidor só faz a ponte entre o telefone e os sites.

## 1. Antes de começar
- Aluga um VPS com Ubuntu 24.04 (ou 22.04) e anota o IP.
- Precisas de um domínio para HTTPS. Cria um registo DNS A, por exemplo relay.teudominio.com → IP do servidor.

## 2. Instalar
```bash
ssh root@IP_DO_SERVIDOR
apt update && apt install -y python3 caddy ufw
adduser --system --group --home /opt/minidb-relay minidb
mkdir -p /opt/minidb-relay && chown minidb:minidb /opt/minidb-relay
```

## 3. Enviar relay.py
A partir do computador onde está o projeto:
```bash
scp relay/relay.py root@IP_DO_SERVIDOR:/opt/minidb-relay/
```

## 4. Serviço systemd
Cria /etc/systemd/system/minidb-relay.service:
```ini
[Unit]
Description=MiniDB Relay
After=network.target
[Service]
User=minidb
WorkingDirectory=/opt/minidb-relay
Environment=HOST=127.0.0.1
Environment=PORT=9000
Environment=RELAY_TOKEN_FILE=/opt/minidb-relay/relay_token.txt
ExecStart=/usr/bin/python3 /opt/minidb-relay/relay.py
Restart=always
[Install]
WantedBy=multi-user.target
```
Depois executa:
```bash
systemctl daemon-reload
systemctl enable --now minidb-relay
cat /opt/minidb-relay/relay_token.txt
```

## 5. HTTPS com Caddy
No /etc/caddy/Caddyfile:
```
relay.teudominio.com {
    request_body { max_size 30MB }
    reverse_proxy 127.0.0.1:9000
}
```
Depois: systemctl reload caddy. O DNS deve apontar para o servidor.

## 6. Firewall e teste
```bash
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
```
Abre https://relay.teudominio.com/ e confirma que aparece “MiniDB Relay a funcionar”.

## Segurança
Não partilhes o token do relay. Nunca coloques uma chave secreta sk_ num site público.
