"""Stdlib-only Windows/Linux IP camera connectivity diagnostic.

Example:
    py camera_network_probe.py 192.168.1.100 --path /live

This checks a camera that YOU own/control. It does not guess stream paths,
credentials, or default passwords.
"""
import argparse
import socket


def tcp_check(ip, port):
    try:
        with socket.create_connection((ip, port), timeout=1.5):
            return True
    except (OSError, TimeoutError):
        return False


def rtsp_request(ip, port, path, method):
    url = f"rtsp://{ip}:{port}{path}"
    request = (f"{method} {url} RTSP/1.0\r\n"
               f"CSeq: 1\r\nUser-Agent: EthernetCameraViewer-Probe\r\n"
               f"Accept: application/sdp\r\n\r\n")
    with socket.create_connection((ip, port), timeout=3) as s:
        s.settimeout(3)
        s.sendall(request.encode("ascii"))
        response = s.recv(6000).decode("latin-1", "replace")
        return response.split("\r\n", 1)[0]


def main():
    p = argparse.ArgumentParser(description="Probe your own local IP camera")
    p.add_argument("ip", help="camera IP on the LAN, e.g. 192.168.1.100")
    p.add_argument("--path", default="/", help="known RTSP stream path, e.g. /live")
    p.add_argument("--rtsp-port", type=int, default=554)
    args = p.parse_args()
    try:
        socket.inet_aton(args.ip)
    except OSError:
        p.error("Use an IPv4 address from your camera/router settings")
    if not args.path.startswith("/"):
        p.error("--path must start with /")

    for port, label in [(80, "HTTP"), (443, "HTTPS"),
                        (args.rtsp_port, "RTSP"), (8080, "Alt HTTP"),
                        (8899, "Possible ONVIF")]:
        print(f"TCP {port} ({label}): {'OPEN' if tcp_check(args.ip, port) else 'closed/no response'}")

    if not tcp_check(args.ip, args.rtsp_port):
        print("\nNo TCP RTSP listener on that port. Verify the camera and its settings.")
        return
    for method in ["OPTIONS", "DESCRIBE"]:
        try:
            status = rtsp_request(args.ip, args.rtsp_port, args.path, method)
            print(f"{method}: {status}")
        except OSError as ex:
            print(f"{method}: error ({type(ex).__name__})")
    print("\nRTSP 200 means that path may be playable; 401 requires authentication;"
          " 404 suggests the path does not exist. A port alone does not prove compatibility.")


if __name__ == "__main__":
    main()
