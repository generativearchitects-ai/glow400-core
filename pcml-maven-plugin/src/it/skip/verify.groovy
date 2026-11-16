def log = new File(basedir, 'build.log').text
assert log.contains('[INFO] pcml:validate skipped: pcml.skip is set') : 'skip says so at info level'
assert !log.contains('OVL012') : 'the invalid repository was not looked at'
assert log.contains('BUILD SUCCESS')
