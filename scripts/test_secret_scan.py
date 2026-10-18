import unittest
from secret_scan_loader import load_rules


class SecretScanContract(unittest.TestCase):
    def test_detects_without_echoing_values(self):
        rules = load_rules()
        # Fragments avoid embedding complete credential examples in source files.
        cases = [
            b"-----BEGIN " + b"PRIVATE KEY-----",
            b"trojan://" + b"dummy@vpn.example:443",
            b"https://provider.example/sub/" + b"A" * 24,
            b'{"password":' + b'"dummy-sensitive-value"}',
        ]
        for value in cases:
            result = rules(value)
            self.assertTrue(result)
            self.assertNotIn(value.decode(), str(result))

    def test_clean_fixture(self):
        self.assertEqual(load_rules()(b"socks5://synthetic:synthetic@vpn.invalid:1080"), [])


if __name__ == "__main__":
    unittest.main()
