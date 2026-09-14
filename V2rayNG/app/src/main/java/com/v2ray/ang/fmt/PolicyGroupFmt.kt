package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.net.URI

object PolicyGroupFmt : FmtBase() {
    /**
     * Parses a group:// URI into a ProfileItem.
     *
     * Format: group://TYPE?sub=GROUP_NAME&filter=FILTER#NAME
     * - TYPE: policy group type index (0 = least ping, 1 = least load, …)
     * - sub: exact group/subscription **name** (remarks), not GUID
     * - filter: optional remarks regex/filter
     * - fragment: display name of this policy-group profile
     *
     * Backward-compatible: if `sub` equals a known subscription GUID, it is still accepted.
     */
    fun parse(str: String): ProfileItem? {
        return try {
            val uri = URI(Utils.fixIllegalUrl(str))
            if (!str.startsWith(AppConfig.GROUP, ignoreCase = true)) {
                return null
            }

            val config = ProfileItem.create(EConfigType.POLICYGROUP)

            // Host or path segment holds the policy group type index
            val typeRaw = when {
                !uri.host.isNullOrBlank() -> uri.host
                !uri.path.isNullOrBlank() -> uri.path.trimStart('/')
                else -> "0"
            }
            config.policyGroupType = typeRaw?.takeIf { it.isNotBlank() } ?: "0"

            val queryParam = if (!uri.rawQuery.isNullOrEmpty()) getQueryParam(uri) else emptyMap()
            val subRaw = queryParam["sub"]?.takeIf { it.isNotBlank() }
            config.policyGroupSubscriptionId = resolveGroupToId(subRaw)
            config.policyGroupFilter = queryParam["filter"]?.takeIf { it.isNotBlank() }

            config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "policy group" }
            val groupLabel = subRaw.orEmpty()
            config.description =
                "${config.policyGroupType} - $groupLabel - ${config.policyGroupFilter.orEmpty()}"

            config
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse group:// URI", e)
            null
        }
    }

    /**
     * Converts a POLICYGROUP ProfileItem to a group:// URI payload (without scheme;
     * caller prepends protocolScheme).
     *
     * Writes the **exact group name** into `sub=`, not the internal GUID.
     */
    fun toUri(config: ProfileItem): String {
        val type = config.policyGroupType?.takeIf { it.isNotBlank() } ?: "0"
        val groupName = resolveIdToGroupName(config.policyGroupSubscriptionId)
        val sub = Utils.encodeURIComponent(groupName)
        val filter = Utils.encodeURIComponent(config.policyGroupFilter.orEmpty())
        val name = Utils.encodeURIComponent(config.remarks)
        return "$type?sub=$sub&filter=$filter#$name"
    }

    /**
     * Maps a group name (or legacy GUID) from the URI to an internal subscription id.
     * Empty / blank → all groups (null).
     */
    private fun resolveGroupToId(subRaw: String?): String? {
        if (subRaw.isNullOrBlank()) return null

        val subscriptions = MmkvManager.decodeSubscriptions()

        // 1) Exact GUID match (legacy links)
        subscriptions.firstOrNull { it.guid == subRaw }?.let { return it.guid }

        // 2) Exact remarks match (preferred: group name)
        subscriptions.firstOrNull { it.subscription.remarks == subRaw }?.let { return it.guid }

        // 3) Case-insensitive remarks match
        subscriptions.firstOrNull {
            it.subscription.remarks.equals(subRaw, ignoreCase = true)
        }?.let { return it.guid }

        // Unknown name — keep as-is so user can fix later; filter will simply match nothing by id
        LogUtil.w(AppConfig.TAG, "group:// sub not found by name/id: $subRaw")
        return subRaw
    }

    /**
     * Maps an internal subscription id to the exact group name for export.
     */
    private fun resolveIdToGroupName(subscriptionId: String?): String {
        if (subscriptionId.isNullOrBlank()) return ""
        val sub = MmkvManager.decodeSubscription(subscriptionId)
        return sub?.remarks?.takeIf { it.isNotBlank() } ?: subscriptionId
    }
}
