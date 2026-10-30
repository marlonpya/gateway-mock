rootProject.name = "gateway-mock"

// Repos hermanos del laboratorio: si están clonados al lado, Gradle los compila
// desde el código (composite build) y no hace falta publicar nada.
listOf("dukpt-kt", "iso8583-kt").forEach { sibling ->
    if (file("../$sibling").exists()) includeBuild("../$sibling")
}
