// Packaged consumer proof, not installation into the developer's normal repository or Central.
def index = new Properties()
new File(basedir, 'target/classes/META-INF/saas-kernel/semantic-index.properties').withInputStream { index.load(it) }
def version = index.getProperty('kernel-version')
assert version == index.getProperty('generator-version')
['saas-kernel', 'saas-kernel-taxi-generator'].each { artifact ->
    assert new File(localRepositoryPath, "io/github/gmcnicol/${artifact}/${version}/${artifact}-${version}.jar").isFile()
}
assert new File(basedir, 'target/adoption-1.0.0.jar').isFile()
def summary = new File(basedir, 'target/failsafe-reports/failsafe-summary.xml').text
assert summary.contains('<completed>5</completed>')
['errors', 'failures', 'skipped'].each { assert summary.contains("<${it}>0</${it}>") }
return true
