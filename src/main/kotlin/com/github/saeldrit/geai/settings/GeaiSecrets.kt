package com.github.saeldrit.geai.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * API-key storage backed by the platform [PasswordSafe] (OS keychain / encrypted store).
 * Keys are scoped per [LlmProvider] so switching providers does not clobber credentials.
 */
object GeaiSecrets {

    private const val SUBSYSTEM = "Geai"

    private fun attributesFor(provider: LlmProvider): CredentialAttributes =
        CredentialAttributes(generateServiceName(SUBSYSTEM, provider.name))

    fun apiKey(provider: LlmProvider): String? =
        PasswordSafe.instance.getPassword(attributesFor(provider))?.takeIf { it.isNotBlank() }

    fun setApiKey(provider: LlmProvider, key: String?) {
        PasswordSafe.instance.setPassword(attributesFor(provider), key?.takeIf { it.isNotBlank() })
    }

    fun hasApiKey(provider: LlmProvider): Boolean = apiKey(provider) != null
}
