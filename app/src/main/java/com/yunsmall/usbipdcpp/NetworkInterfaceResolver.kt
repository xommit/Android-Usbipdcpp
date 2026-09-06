package com.yunsmall.usbipdcpp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address

/**
 * Types d'interfaces pouvant être utilisées par le serveur USB/IP.
 *
 * ALL conserve le comportement historique : écoute sur 0.0.0.0.
 */
enum class ListenInterfaceType {
    ALL,
    VPN,
    WIFI,
    ETHERNET
}

/**
 * Adresse IPv4 locale réellement disponible pour l'écoute.
 *
 * Aucun libellé utilisateur n'est stocké ici afin que les traductions restent
 * gérées dans les ressources Android.
 */
data class ListenAddressOption(
    val type: ListenInterfaceType,
    val address: String,
    val interfaceName: String?
)

/**
 * Détecte les adresses locales à partir des réseaux connus d'Android.
 *
 * Important : un VPN Android peut également annoncer son transport sous-jacent
 * (Wi-Fi, Ethernet, mobile...). La détection VPN doit donc toujours être faite
 * avant la détection Wi-Fi/Ethernet.
 */
object NetworkInterfaceResolver {

    private const val TAG = "NetworkInterfaceResolver"
    const val ALL_INTERFACES_ADDRESS = "0.0.0.0"

    /**
     * Retourne toutes les adresses d'écoute actuellement utilisables.
     *
     * ALL / 0.0.0.0 est toujours présent afin de conserver le comportement
     * historique de l'application.
     */
    fun getAvailableAddresses(context: Context): List<ListenAddressOption> {
        val result = mutableListOf(
            ListenAddressOption(
                type = ListenInterfaceType.ALL,
                address = ALL_INTERFACES_ADDRESS,
                interfaceName = null
            )
        )

        val connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

        try {
            connectivityManager.allNetworks.forEach { network ->
                val capabilities =
                    connectivityManager.getNetworkCapabilities(network)
                        ?: return@forEach

                val type = classifyNetwork(capabilities)
                    ?: return@forEach

                val linkProperties =
                    connectivityManager.getLinkProperties(network)
                        ?: return@forEach

                linkProperties.linkAddresses
                    .asSequence()
                    .map { it.address }
                    .filterIsInstance<Inet4Address>()
                    .filter { address ->
                        !address.isAnyLocalAddress &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    }
                    .forEach { address ->
                        result += ListenAddressOption(
                            type = type,
                            address = address.hostAddress ?: return@forEach,
                            interfaceName = linkProperties.interfaceName
                        )
                    }
            }
        } catch (e: SecurityException) {
            // ACCESS_NETWORK_STATE manquant ou réseau non accessible.
            // ALL reste disponible afin de préserver le comportement historique.
            Log.e(TAG, "Unable to inspect Android networks", e)
        } catch (e: RuntimeException) {
            // Défense contre un réseau qui disparaît pendant l'énumération.
            Log.w(TAG, "Network list changed while enumerating interfaces", e)
        }

        return result
            .distinctBy { Triple(it.type, it.address, it.interfaceName) }
            .sortedWith(
                compareBy<ListenAddressOption>(
                    { it.type.ordinal },
                    { it.interfaceName.orEmpty() },
                    { it.address }
                )
            )
    }

    /**
     * Retourne les adresses correspondant à un type particulier.
     */
    fun getAvailableAddresses(
        context: Context,
        type: ListenInterfaceType
    ): List<ListenAddressOption> {
        return getAvailableAddresses(context)
            .filter { it.type == type }
    }

    /**
     * Résout l'adresse qui devra être transmise au serveur natif.
     *
     * - ALL retourne toujours 0.0.0.0.
     * - Si preferredAddress est encore présente sur le type demandé, elle est
     *   conservée.
     * - S'il n'existe qu'une seule adresse pour le type demandé, elle peut être
     *   choisie automatiquement.
     * - Avec zéro ou plusieurs adresses sans préférence valide, retourne null :
     *   l'appelant doit refuser le démarrage ou demander un choix explicite.
     */
    fun resolveListenAddress(
        context: Context,
        type: ListenInterfaceType,
        preferredAddress: String? = null
    ): String? {
        if (type == ListenInterfaceType.ALL) {
            return ALL_INTERFACES_ADDRESS
        }

        val candidates = getAvailableAddresses(context, type)

        if (!preferredAddress.isNullOrBlank()) {
            candidates.firstOrNull { it.address == preferredAddress }
                ?.let { return it.address }
        }

        return if (candidates.size == 1) {
            candidates.first().address
        } else {
            null
        }
    }

    /**
     * Vérifie juste avant le démarrage qu'une adresse choisie appartient toujours
     * au type d'interface sélectionné.
     *
     * Cela évite de démarrer silencieusement sur une autre interface lorsqu'un
     * VPN, le Wi-Fi ou l'Ethernet vient de disparaître.
     */
    fun isListenAddressAvailable(
        context: Context,
        type: ListenInterfaceType,
        address: String
    ): Boolean {
        if (type == ListenInterfaceType.ALL) {
            return address == ALL_INTERFACES_ADDRESS
        }

        return getAvailableAddresses(context, type)
            .any { it.address == address }
    }

    /**
     * Classe un réseau Android.
     *
     * VPN doit rester prioritaire : Android peut indiquer TRANSPORT_VPN ainsi
     * que Wi-Fi/Ethernet pour le réseau sous-jacent.
     */
    private fun classifyNetwork(
        capabilities: NetworkCapabilities
    ): ListenInterfaceType? {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                ListenInterfaceType.VPN

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                ListenInterfaceType.WIFI

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                ListenInterfaceType.ETHERNET

            else -> null
        }
    }
}
