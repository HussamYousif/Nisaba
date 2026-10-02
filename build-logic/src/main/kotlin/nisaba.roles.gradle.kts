tasks.register<VerifyFileRolesTask>("verifyFileRoles") {
    group = "verification"
    description = "Checks file names against the role-suffix convention (DESIGN §3.3)"
    sources.from(fileTree("src") { include("**/*.kt", "**/*.java") })
}
tasks.matching { it.name == "check" }.configureEach { dependsOn("verifyFileRoles") }
