import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location('published_consumer', Path(__file__).parents[1] / 'verify-published-consumer.py')
consumer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(consumer)


class PublishedConsumerTests(unittest.TestCase):
    def test_new_consumer_has_pinned_version_empty_repository_and_no_secret_values(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / 'proof'
            with patch.dict('os.environ', {'GITHUB_TOKEN': 'not-a-real-token'}):
                application, repository = consumer.prepare_consumer(destination, '0.2.0')
            pom = (application / 'pom.xml').read_text()
            self.assertIn('<kernel.version>0.2.0</kernel.version>', pom)
            self.assertNotIn('@project.version@', pom)
            self.assertFalse(repository.exists())
            self.assertFalse((application / 'target').exists())
            settings = (destination / 'settings.xml').read_text()
            ET.fromstring(settings)
            self.assertIn('${env.GITHUB_TOKEN}', settings)
            self.assertNotIn('not-a-real-token', settings)
            self.assertEqual((destination / 'settings.xml').stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                consumer.prepare_consumer(destination, '0.2.0')

    def test_missing_version_token_fails_instead_of_using_a_snapshot(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'source'
            fixture = root / 'taxi-generator/src/it/adoption'
            fixture.mkdir(parents=True)
            (fixture / 'pom.xml').write_text('<project/>')
            with patch.object(consumer, 'ROOT', root):
                with self.assertRaisesRegex(ValueError, 'one explicit Kernel version token'):
                    consumer.prepare_consumer(Path(temporary) / 'consumer', '0.2.0')


if __name__ == '__main__':
    unittest.main()
