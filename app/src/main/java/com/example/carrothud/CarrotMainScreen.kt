private suspend fun findCommaDeviceIp(): String? = withContext(Dispatchers.IO) {
    val candidateSubnets = (
        getLocalSubnets() +
        listOf(
            "192.168.43",
            "192.168.42",
            "192.168.137",
            "192.168.225",
            "172.20.10",
            "10.42.0",
            "192.168.0",
            "192.168.1",
            "192.168.8"
        )
    ).distinct()

    for (subnet in candidateSubnets) {
        for (i in 1..254) {
            val ip = "$subnet.$i"

            if (isPortOpen(ip, 7000, 80)) {
                saveCustomLog("Comma found: $ip")
                return@withContext ip
            }
        }
    }

    return@withContext null
}

private fun getLocalSubnets(): List<String> {
    val result = mutableListOf<String>()

    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()

        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()

            val addresses = networkInterface.inetAddresses

            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                val host = address.hostAddress ?: continue

                if (!address.isLoopbackAddress &&
                    host.contains(".") &&
                    !host.contains(":")
                ) {
                    val parts = host.split(".")

                    if (parts.size == 4) {
                        result.add(
                            "${parts[0]}.${parts[1]}.${parts[2]}"
                        )
                    }
                }
            }
        }
    } catch (e: Exception) {
        saveCustomLog(
            "getLocalSubnets error: ${e.localizedMessage}"
        )
    }

    return result.distinct()
}

private fun isPortOpen(
    ip: String,
    port: Int,
    timeout: Int
): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(ip, port),
                timeout
            )
        }
        true
    } catch (e: Exception) {
        false
    }
}
