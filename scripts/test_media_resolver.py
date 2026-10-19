#!/usr/bin/env python3
"""Offline contract tests of the optional adapter's socket deny boundary."""
import importlib.util
import pathlib
import os
import shutil
import subprocess
import sys
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'internal/media/resolver.py'

class ResolverTests(unittest.TestCase):
    def test_direct_socket_and_dns_denied(self):
        code = '''import runpy,socket
m=runpy.run_path(%r)
m['guard']({'address':'127.0.0.1:12345'})
for addr in [('127.0.0.1',54321),('1.1.1.1',443)]:
 s=socket.socket()
 try: s.connect(addr)
 except OSError as e: assert str(e)=='direct_connection_forbidden'
 else: raise AssertionError('direct allowed')
 finally: s.close()
s=socket.socket(type=socket.SOCK_DGRAM)
try: s.sendto(b'synthetic',('127.0.0.1',54321))
except OSError as e: assert str(e)=='direct_datagram_forbidden'
else: raise AssertionError('UDP allowed')
finally: s.close()
try: socket.getaddrinfo('example.invalid',443)
except OSError as e: assert str(e)=='direct_dns_forbidden'
else: raise AssertionError('DNS allowed')
''' % str(SCRIPT)
        p = subprocess.run([sys.executable, '-I', '-c', code], capture_output=True, timeout=5)
        self.assertEqual(p.returncode, 0, p.stderr.decode())

    def test_node_network_and_children_denied(self):
        js = shutil.which('node')
        self.assertIsNotNone(js)
        code = """import runpy,subprocess
m=runpy.run_path(%r)
m['guard_process'](%r)
p=subprocess.run([%r,'--permission','-'],input="try { require('node:dns').resolve4('example.invalid'); process.exit(9) } catch(e) { if(e.message!='resolver_js_network_forbidden') process.exit(8) }",text=True,capture_output=True,timeout=5)
assert p.returncode==0,p.stderr
p=subprocess.run([%r,'--permission','-'],input="try { require('node:net').connect(1,'127.0.0.1'); process.exit(9) } catch(e) { if(e.message!='resolver_js_network_forbidden') process.exit(8) }",text=True,capture_output=True,timeout=5)
assert p.returncode==0,p.stderr
try: subprocess.run(['/bin/sh','-c','true'])
except OSError as e: assert str(e)=='resolver_child_forbidden'
else: raise AssertionError('child allowed')
""" % (str(SCRIPT), js, js, js)
        p = subprocess.run([sys.executable, '-I', '-c', code], capture_output=True, timeout=10)
        self.assertEqual(p.returncode, 0, p.stderr.decode())

    def test_pinned_client_guard(self):
        python = os.environ.get('VPN_SWEEP_TEST_PYTHON')
        if not python:
            self.skipTest('explicit isolated optional dependency not provided')
        code = """import runpy
m=runpy.run_path(%r)
m['pin_client']()
from yt_dlp.extractor.youtube import YoutubeIE
for name in ('_download_ytcfg','_extract_player_response'):
 for client in ('web_embedded','visionos','tv_downgraded'):
  try: getattr(YoutubeIE,name)(None,client)
  except PermissionError as e: assert str(e)=='resolver_client_switch_forbidden'
  else: raise AssertionError('fallback allowed')
""" % str(SCRIPT)
        p = subprocess.run([python, '-I', '-c', code], capture_output=True, timeout=10)
        self.assertEqual(p.returncode, 0, p.stderr.decode())

    def test_no_raw_error_output(self):
        p = subprocess.run([sys.executable, '-I', str(SCRIPT)], input=b'{"secret":"synthetic"}', capture_output=True, timeout=5)
        self.assertEqual(p.returncode, 0)
        self.assertNotIn(b'synthetic', p.stdout + p.stderr)
        self.assertEqual(p.stderr, b'')
        self.assertIn(b'resolver_blocked', p.stdout)

if __name__ == '__main__':
    unittest.main()
