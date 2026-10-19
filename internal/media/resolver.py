# Executed by an explicitly selected isolated Python (-I); never imports user config/plugins.
import hashlib
import importlib.metadata
import ipaddress
import json
import os
import pathlib
import socket
import ssl
import subprocess
import sys


def identity(js):
    import yt_dlp
    import yt_dlp_ejs
    digest = hashlib.sha256()
    for module in (yt_dlp, yt_dlp_ejs):
        root = pathlib.Path(module.__file__).parent
        for p in sorted(root.rglob('*')):
            if p.is_file() and '__pycache__' not in p.parts:
                digest.update(str(p.relative_to(root)).encode())
                digest.update(b'\0')
                digest.update(p.read_bytes())
    def sha(path):
        return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()
    version = subprocess.run([js, '--version'], check=True, capture_output=True,
                             timeout=5).stdout.decode().strip()
    return dict(python_sha256=sha(sys.executable), js_sha256=sha(js),
                python_version=sys.version.split()[0], js_version=version,
                yt_dlp=importlib.metadata.version('yt-dlp'),
                ejs=importlib.metadata.version('yt-dlp-ejs'),
                package_sha256=digest.hexdigest(), adapter_version=1, adapter_sha256=sha(__file__))


def guard(proxy):
    # All Python network sockets must connect to this exact numeric loopback SOCKS.
    host, port = proxy['address'].rsplit(':', 1)
    host = host.strip('[]')
    if not ipaddress.ip_address(host).is_loopback:
        raise ValueError('proxy')
    port = int(port)
    connect = socket.socket.connect
    def checked(sock, address):
        if (not isinstance(address, tuple) or address[0] != host or address[1] != port
                or sock.type != socket.SOCK_STREAM):
            raise OSError('direct_connection_forbidden')
        return connect(sock, address)
    def connect_ex(sock, address):
        try:
            checked(sock, address)
            return 0
        except OSError:
            return 13
    gai = socket.getaddrinfo
    def local_gai(name, service, *args, **kwargs):
        if name != host:
            raise OSError('direct_dns_forbidden')
        return gai(name, service, *args, **kwargs)
    socket.socket.connect = checked
    socket.socket.connect_ex = connect_ex
    def no_datagrams(*args, **kwargs):
        raise OSError("direct_datagram_forbidden")
    socket.socket.sendto = no_datagrams
    socket.getaddrinfo = local_gai
    # Bound decrypted HTTP response bytes across metadata requests, not just stdout.
    read = ssl.SSLSocket.read
    counter = [0]
    def bounded(sock, length=1024, buffer=None):
        length = min(length, max(1, 2 * 1024 * 1024 - counter[0] + 1))
        result = read(sock, length, buffer)
        counter[0] += result if isinstance(result, int) else len(result)
        if counter[0] > 2 * 1024 * 1024:
            raise OSError('resolver_network_limit')
        return result
    ssl.SSLSocket.read = bounded
    return counter


# Node's permission mode does not itself prohibit networking. Deny all normal
# network entry points before the pinned EJS code runs; no child tools allowed.
NODE_GUARD = r"""
const deny = () => { throw new Error('resolver_js_network_forbidden'); };
require('node:net').Socket.prototype.connect = deny;
require('node:net').connect = deny;
require('node:net').createConnection = deny;
require('node:tls').connect = deny;
require('node:dgram').createSocket = deny;
for (const api of [require('node:dns'), require('node:dns').promises]) {
  for (const key of Object.keys(api)) {
    if (typeof api[key] === 'function') api[key] = deny;
  }
}
globalThis.fetch = deny;
require('node:module').syncBuiltinESMExports();
"""


def guard_process(js):
    original_init = subprocess.Popen.__init__
    original_communicate = subprocess.Popen.communicate
    def checked_init(self, args, *a, **kw):
        version = args == [js, '--version']
        if (not isinstance(args, (list, tuple)) or args[0] != js
                or (not version and (args[-1] != '-' or '--permission' not in args))
                or kw.get('shell') or kw.get('start_new_session') or kw.get('executable')):
            raise OSError('resolver_child_forbidden')
        original_init(self, args, *a, **kw)
    def checked_communicate(self, input=None, timeout=None):
        if input is not None and self.args[-1] == '-':
            input = NODE_GUARD + input
        return original_communicate(self, input, timeout)
    subprocess.Popen.__init__ = checked_init
    subprocess.Popen.communicate = checked_communicate


def pin_client():
    from yt_dlp.extractor.youtube import YoutubeIE
    for name in ('_download_ytcfg', '_extract_player_response'):
        original = getattr(YoutubeIE, name)
        def checked(self, client, *args, _original=original, **kwargs):
            if client != 'web':
                raise PermissionError('resolver_client_switch_forbidden')
            return _original(self, client, *args, **kwargs)
        setattr(YoutubeIE, name, checked)


def resolve(req):
    from urllib.parse import quote, urlsplit
    import yt_dlp
    proxy = req['proxy']
    counter = guard(proxy)
    guard_process(req['js'])
    pin_client()
    auth = quote(proxy['user'], safe='') + ':' + quote(proxy['pass'], safe='')
    args = ['--ignore-config', '--no-plugin-dirs', '--no-remote-components',
            '--no-js-runtimes', '--js-runtimes', 'node:' + req['js'],
            '--no-cache-dir', '--no-playlist', '--skip-download', '--quiet',
            '--no-warnings', '--socket-timeout', '10', '--retries', '0',
            '--extractor-retries', '0', '--proxy', 'socks5://' + auth + '@' + proxy['address'],
            '--', 'https://www.youtube.com/watch?v=' + req['video_id']]
    opts = yt_dlp.parse_options(args).ydl_opts
    yt_dlp.globals.plugin_dirs.value = []
    yt_dlp.globals.all_plugins_loaded.value = True
    class Quiet:
        def debug(self, *args): pass
        def warning(self, *args): pass
        def error(self, *args): pass
    opts['logger'] = Quiet()
    opts['ignoreerrors'] = False
    opts['extractor_args'] = {'youtube': {'player_client': ['web']}}
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info('https://www.youtube.com/watch?v=' + req['video_id'], download=False)
    if not isinstance(info, dict):
        raise ValueError('unsupported')
    if info.get('id') != req['video_id'] or info.get('is_live') or info.get('_type', 'video') != 'video':
        raise ValueError('unsupported')
    formats = []
    for f in info.get('formats', []):
        u = urlsplit(f.get('url', ''))
        if (f.get('protocol') != 'https' or f.get('ext') != 'mp4' or f.get('has_drm')
                or (f.get('height') or 0) < req['min_height'] or f.get('vcodec') in (None, 'none')
                or u.scheme != 'https' or not u.hostname or not u.hostname.endswith('.googlevideo.com')
                or u.username or u.password or u.fragment or u.port not in (None, 443)):
            continue
        formats.append(f)
    if not formats:
        return dict(error='resolver_unsupported', network_bytes=counter[0])
    # Deterministic choice; never change client to overcome denial.
    f = sorted(formats, key=lambda f: (f['height'], str(f.get('format_id', ''))))[0]
    return dict(url=f['url'], format_id=str(f['format_id']), height=f['height'], network_bytes=counter[0])


def failure_code(error):
    # Classification only; exception text/URLs never leave this process.
    text = str(error).lower()
    if any(x in text for x in ('sign in', 'not a bot', 'captcha', 'login required')):
        return 'resolver_auth_or_challenge'
    if 'po token' in text or 'po_token' in text:
        return 'resolver_token_required'
    if 'drm' in text:
        return 'resolver_unsupported'
    if any(x in text for x in ('connection refused', 'timed out', 'network is unreachable', 'connection reset')):
        return 'resolver_network_failed'
    return 'resolver_blocked'


def main():
    req = json.loads(sys.stdin.buffer.read(16385))
    ident = identity(req['js'])
    if ident['yt_dlp'] != '2026.8.19' or ident['ejs'] != '0.8.0':
        raise ValueError('version')
    if req.get('identity_only'):
        return ident
    if ident != req['identity']:
        return dict(error='resolver_identity_changed')
    return resolve(req)


if __name__ == '__main__':
    try:
        result = main()
    except Exception as error:
        result = dict(error=failure_code(error), network_bytes=2 * 1024 * 1024 + 1)
    sys.stdout.write(json.dumps(result, separators=(',', ':')))
