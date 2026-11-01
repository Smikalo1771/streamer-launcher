"""Ask a Cast receiver for its device-auth signature, the check Chrome makes before it lists the TV.

Usage: uvx --with pychromecast python -I tv/cast-auth-check.py <tv ip>
"AUTH OK" with a certificate of some hundred bytes means Chrome can cast; "cert 0 B" means the receiver lost
its keys (run tv/castwatch.sh on the TV, or ./cast-fix.sh from the PC).
"""
import socket, ssl, struct, sys
from pychromecast.generated import cast_channel_pb2 as pb
ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
s = ctx.wrap_socket(socket.create_connection((sys.argv[1], 8009), 10))
def send(ns, payload, binary=False):
    m = pb.CastMessage(); m.protocol_version = 0; m.source_id="sender-0"; m.destination_id="receiver-0"; m.namespace=ns
    if binary: m.payload_type=1; m.payload_binary=payload
    else: m.payload_type=0; m.payload_utf8=payload
    b = m.SerializeToString(); s.sendall(struct.pack(">I", len(b)) + b)
dam = pb.DeviceAuthMessage(); dam.challenge.SetInParent()
send("urn:x-cast:com.google.cast.tp.deviceauth", dam.SerializeToString(), True)
def recv(n):
    d=b""
    while len(d)<n: d+=s.recv(n-len(d))
    return d
for _ in range(3):
    m = pb.CastMessage(); m.ParseFromString(recv(struct.unpack(">I", recv(4))[0]))
    if m.namespace.endswith("deviceauth"):
        r = pb.DeviceAuthMessage(); r.ParseFromString(m.payload_binary)
        if r.HasField("error"): print("AUTH ERROR:", r.error)
        else: print("AUTH OK: cert", len(r.response.client_auth_certificate), "B, sig", len(r.response.signature), "B, intermediates", len(r.response.intermediate_certificate))
        break
