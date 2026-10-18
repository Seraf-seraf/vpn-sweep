import importlib.util
import pathlib


def load_rules():
    path = pathlib.Path(__file__).with_name("secret-scan.py")
    spec = importlib.util.spec_from_file_location("secret_scan", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.content_rules
