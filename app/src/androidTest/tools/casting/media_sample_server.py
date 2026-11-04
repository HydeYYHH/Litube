"""Serve one local H.264/AAC sample with byte ranges for Cast/DLNA interoperability tests.

python app/src/androidTest/tools/casting/media_sample_server.py --file out/cast-media-sample/sample.mp4 --port 48081
Use --directory for a folder containing generated .mpd/.m4s DASH samples.
Only media files in that folder are served; the surrounding filesystem is never exposed.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    media = parser.add_mutually_exclusive_group(required=True)
    media.add_argument('--file', type=Path)
    media.add_argument('--directory', type=Path)
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=48081)
    args = parser.parse_args()
    source_file = args.file.resolve(strict=True) if args.file else None
    root = args.directory.resolve(strict=True) if args.directory else None
    mime_types = {'.mp4': 'video/mp4', '.mpd': 'application/dash+xml', '.m4s': 'video/iso.segment'}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def handle(self):
            try:
                super().handle()
            except ConnectionError:
                pass  # Receiver may close a pooled socket while replacing its media.

        def do_HEAD(self):
            self.serve(head=True)

        def do_GET(self):
            self.serve(head=False)

        def serve(self, head):
            name = self.path.split('?', 1)[0].removeprefix('/')
            source = source_file if name == 'sample.mp4' and source_file else (root / name if root else None)
            if (not name or '/' in name or '\\' in name or source is None or
                    source.suffix not in mime_types or not source.is_file()):
                self.send_error(404)
                return
            length = source.stat().st_size
            start, end = 0, length - 1
            requested = self.headers.get('Range')
            if requested:
                match = re.fullmatch(r'bytes=(\d*)-(\d*)', requested.strip())
                if match and any(match.groups()):
                    first, last = match.groups()
                    if first:
                        start = int(first)
                        end = min(int(last), end) if last else end
                    else:
                        start = max(0, length - int(last))
                if not match or not any(match.groups()) or start > end or start >= length:
                    self.send_response(416)
                    self.send_header('Content-Range', f'bytes */{length}')
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
            self.send_response(206 if requested else 200)
            self.send_header('Content-Type', mime_types[source.suffix])
            self.send_header('Content-Length', str(end - start + 1))
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Connection', 'close')
            self.close_connection = True
            if requested:
                self.send_header('Content-Range', f'bytes {start}-{end}/{length}')
            self.end_headers()
            if not head:
                try:
                    with source.open('rb') as media:
                        media.seek(start)
                        remaining = end - start + 1
                        while remaining:
                            chunk = media.read(min(65536, remaining))
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            remaining -= len(chunk)
                except (ConnectionError, BrokenPipeError):
                    pass  # A renderer may abandon a range when seeking/stopping.

    with ThreadingHTTPServer((args.bind, args.port), Handler) as server:
        print(f'Serving samples from {source_file or root} at http://{args.bind}:{args.port}/', flush=True)
        server.serve_forever()


if __name__ == '__main__':
    main()
