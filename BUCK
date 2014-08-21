include_defs('//bucklets/gerrit_plugin.bucklet')

gerrit_plugin(
  name = 'reviewnotes',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/resources/**/*']),
  manifest_entries = [
    'Gerrit-Module: com.googlesource.gerrit.plugins.reviewnotes.ReviewNotesModule',
    'Gerrit-SshModule: com.googlesource.gerrit.plugins.reviewnotes.SshModule'
  ]
)

java_library(
  name = 'classpath',
  deps = [':reviewnotes__plugin'],
)
