def log = new File(basedir, 'build.log').text
assert log.contains('BUILD SUCCESS')
assert log.contains('[INFO] pcml:bundle: attached bundle-1.0.rpgapi with type rpgapi')

def bundle = new File(basedir, 'target/bundle-1.0.rpgapi')
assert bundle.isFile() : 'the bundle is named after the project'

// attached with the type rpgapi: the install plugin put it in the local repository next to the pom
def installed = new File(localRepositoryPath, 'it/pcml/bundle/1.0/bundle-1.0.rpgapi')
assert installed.isFile() : 'the bundle was attached to the project'
assert installed.bytes == bundle.bytes

// byte for byte what the command-line tool produces for the same input
def cli = new File(cliJar)
assert cli.isFile() : "the command-line jar is not built yet: ${cliJar}"
def process = new ProcessBuilder([System.getProperty('java.home') + '/bin/java', '-jar', cliJar, 'bundle', '--repo', '.',
        '--name', 'bundle', '--version', '1.0', '--source-repository', 'https://example.test/orders', '--source-revision', 'abc123',
        '--out', 'cli/bundle.rpgapi']).directory(basedir).redirectErrorStream(true).start()
def output = process.inputStream.text
assert process.waitFor() == 0 : output
assert new File(basedir, 'cli/bundle.rpgapi').bytes == bundle.bytes : 'the same bundle as the command-line tool makes'
