// a released bundle of the repository as it is, made by the command-line tool; then a breaking change without a new major version
def process = new ProcessBuilder([System.getProperty('java.home') + '/bin/java', '-jar', cliJar, 'bundle', '--repo', '.',
        '--name', 'orders', '--version', 'r1', '--out', 'released.rpgapi']).directory(basedir).redirectErrorStream(true).start()
def output = process.inputStream.text
assert process.waitFor() == 0 : output
def api = new File(basedir, 'apis/ORDSTATUS/api.yaml')
def text = api.text
assert text.contains('P0STAT: { name: status }')
api.text = text.replace('P0STAT: { name: status }', 'P0STAT: { name: state }')
return true
