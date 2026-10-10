"""Local IPv4 interfaces."""
from __future__ import annotations

import ipaddress
import socket

import ifaddr

_SKIP = ("vEthernet", "VirtualBox", "VMware", "Hyper-V", "Loopback", "WSL", "docker", "utun", "awdl", "llw", "bridge",
         "tun", "tap", "VPN", "WireGuard", "Tunnel", "ZeroTier", "Tailscale", "OpenVPN", "Wintun", "Radmin", "Hamachi")


def primary_ip() -> str:
    """IP of the interface that holds the default route (no packets are sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return ""
    finally:
        s.close()


def list_ipv4() -> list[dict]:
    """[{name, ip, prefix}] for usable private IPv4 addresses, primary first."""
    out = []
    for ad in ifaddr.get_adapters():
        name = ad.nice_name or ad.name
        for ip in ad.ips:
            if not isinstance(ip.ip, str):
                continue
            addr = ipaddress.IPv4Address(ip.ip)
            if addr.is_loopback or addr.is_link_local or not addr.is_private:
                continue
            virtual = ip.network_prefix >= 30 or any(k.lower() in name.lower() for k in _SKIP)
            out.append({"name": name, "ip": ip.ip, "prefix": ip.network_prefix, "virtual": virtual})
    prim = primary_ip()
    out.sort(key=lambda x: (x["virtual"], x["ip"] != prim, x["ip"]))
    return out


def pick_ip(preferred: str = "auto") -> str:
    ips = list_ipv4()
    if preferred != "auto" and any(x["ip"] == preferred for x in ips):
        return preferred
    real = [x for x in ips if not x["virtual"]]
    return (real or ips or [{"ip": ""}])[0]["ip"]


def same_subnet(a: str, b: str, prefix: int = 24) -> bool:
    try:
        return ipaddress.IPv4Interface(f"{a}/{prefix}").network == ipaddress.IPv4Interface(f"{b}/{prefix}").network
    except ValueError:
        return False


def broadcast_addr(ip: str, prefix: int) -> str:
    try:
        return str(ipaddress.IPv4Interface(f"{ip}/{prefix}").network.broadcast_address)
    except ValueError:
        return ""
