def generated = new File(basedir, 'target/generated-sources/taxi/example/bindings/customer/crm/Customer.java')
def compiled = new File(basedir, 'target/classes/example/Consumer.class')
def wrapper = new File(basedir, 'target/generated-sources/taxi/example/bindings/customer/crm/CustomerId.java')
assert generated.isFile()
assert compiled.isFile()
assert !generated.text.contains('io.github.gmcnicol.kernel.internal')
assert wrapper.text.contains('@JsonValue')
assert wrapper.text.contains('JsonCreator.Mode.DELEGATING')
assert new File(basedir, 'build.log').text.contains('linter rule no-primitive-types-on-models')

def hashes = {
    def digest = java.security.MessageDigest.getInstance('SHA-256')
    def root = new File(basedir, 'target/generated-sources/taxi')
    def files = []
    root.eachFileRecurse(groovy.io.FileType.FILES) { files << it }
    files.sort { it.path }.each { file ->
        digest.update(root.toPath().relativize(file.toPath()).toString().bytes)
        digest.update(file.bytes)
    }
    digest.digest()
}
def first = hashes()
def maven = new File(System.getProperty('maven.home'), 'bin/mvn').absolutePath
def command = [maven, '-q', "-Dmaven.repo.local=${localRepositoryPath}", 'clean', 'generate-sources'].collect { it.toString() }
assert new ProcessBuilder(command).directory(basedir).inheritIO().start().waitFor() == 0
assert java.util.Arrays.equals(first, hashes())

def taxi = new File(basedir, 'src/main/taxi/customer.taxi')
taxi.text = taxi.text.replace('\nmodel Removable {}\n', '\n')
assert new ProcessBuilder(command.dropRight(2) + ['generate-sources']).directory(basedir).inheritIO().start().waitFor() == 0
assert !new File(basedir, 'target/generated-sources/taxi/example/bindings/customer/crm/Removable.java').exists()
