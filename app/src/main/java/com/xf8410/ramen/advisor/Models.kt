package com.xf8410.ramen.advisor

import com.google.gson.annotations.SerializedName

/**
 * hlpatch /summary 端点返回的 JSON 数据模型
 * 只包含拉面杯分析需要的字段
 */
data class SummaryResponse(
    @SerializedName("scenario") val scenario: String? = null,
    @SerializedName("raw_total_turn_num") val turnNum: Int = 0,
    @SerializedName("chara_info") val charaInfo: CharaInfo? = null,
    @SerializedName("ramen") val ramen: RamenData? = null,
    @SerializedName("trainings") val trainings: List<TrainingInfo>? = null,
)

data class CharaInfo(
    @SerializedName("speed") val speed: Int = 0,
    @SerializedName("stamina") val stamina: Int = 0,
    @SerializedName("power") val power: Int = 0,
    @SerializedName("guts") val guts: Int = 0,
    @SerializedName("wiz") val wiz: Int = 0,
    @SerializedName("vital") val vital: Int = 0,
    @SerializedName("max_vital") val maxVital: Int = 0,
    @SerializedName("motivation") val motivation: Int = 0,
    @SerializedName("skill_point") val skillPoint: Int = 0,
)

data class RamenData(
    @SerializedName("ramen_values") val values: Map<String, Int>? = null,
    @SerializedName("feeling_info") val feelingInfo: List<FeelingItem>? = null,
    @SerializedName("feeling_turn_info") val feelingTurnInfo: List<FeelingTurnItem>? = null,
    @SerializedName("feeling_reduce_turn_info") val feelingReduceTurnInfo: List<FeelingReduceItem>? = null,
    @SerializedName("command_feeling_info") val commandFeelingInfo: List<CommandFeelingItem>? = null,
    @SerializedName("active_effects") val activeEffects: List<ActiveEffect>? = null,
    @SerializedName("uraf_effect") val urafEffect: UrafEffect? = null,
    @SerializedName("selected_region_ids") val selectedRegionIds: List<Int>? = null,
    @SerializedName("all_selected_region_ids") val allSelectedRegionIds: List<Int>? = null,
) {
    val checkpointPt: Int get() = values?.get("CheckPointPt") ?: 0
    val expectedCheckpointPt: Int get() = values?.get("ExpectedCheckPointPt") ?: 0
    val specialFeelingNum: Int get() = values?.get("SpecialFeelingNum") ?: 0
    val recommendType: Int get() = values?.get("RecommendType") ?: 0
}

data class FeelingItem(
    @SerializedName("feeling_id") val feelingId: Int = 0,
    @SerializedName("remaining") val remaining: Int = 0,
)

data class FeelingTurnItem(
    @SerializedName("feeling_id") val feelingId: Int = 0,
    @SerializedName("remaining") val remaining: Int = 0,
)

data class FeelingReduceItem(
    @SerializedName("feeling_id") val feelingId: Int = 0,
    @SerializedName("remaining") val remaining: Int = 0,
)

data class CommandFeelingItem(
    @SerializedName("CommandType") val commandType: Int = 0,
    @SerializedName("CommandId") val commandId: Int = 0,
    @SerializedName("FeelingId") val feelingId: Int = 0,
)

data class ActiveEffect(
    @SerializedName("EffectCategory") val effectCategory: Int = 0,
    @SerializedName("EffectId") val effectId: Int = 0,
    @SerializedName("EffectValue") val effectValue: Int = 0,
)

data class UrafEffect(
    @SerializedName("UrafEffectType") val type: Int = 0,
    @SerializedName("UrafEffectState") val state: Int = 0,
)

data class TrainingInfo(
    @SerializedName("name") val name: String? = null,
    @SerializedName("command_id") val commandId: Int = 0,
    @SerializedName("train_type") val trainType: Int = 0,
    @SerializedName("speed") val speed: Int = 0,
    @SerializedName("stamina") val stamina: Int = 0,
    @SerializedName("power") val power: Int = 0,
    @SerializedName("guts") val guts: Int = 0,
    @SerializedName("wiz") val wiz: Int = 0,
    @SerializedName("is_enable") val isEnable: Int = 0,
    @SerializedName("failure_rate") val failureRate: Int = 0,
    @SerializedName("heads") val heads: Int = 0,
    @SerializedName("shining") val shining: Int = 0,
    @SerializedName("partner_ids") val partnerIds: List<Int>? = null,
    @SerializedName("partners") val partners: List<SupportCardPartner>? = null,
    @SerializedName("gains") val gains: Map<String, Int>? = null,
)

/**
 * 训练中出现的每个支援卡伙伴
 *
 * SO /summary 输出，每个 training entry 的 partners 数组元素。
 *
 * 字段含义：
 * - current_bond: 当前羁绊值
 * - is_shining: 是否彩圈（当前训练可触发友情训练）
 * - is_unique_active: 固有效果是否已激活（current_bond >= bond_threshold）
 * - bond_threshold: 固有效果激活羁绊门槛（MDB support_card_unique_effect type_0=101 value_0）
 * - support_card_type: MDB 卡类别（1=普通, 2=友人, 3=团体）
 *   注意：与 cardDB.json 的 cardType（0=速,1=耐,...,5=友,6=团）不同！
 * - is_tips_event: 是否灵感事件（hint event）
 * - partner_type: 伙伴类型（1=支援卡, 0=NPC/理事长/记者等）
 * - bond_gain: 本次训练羁绊增量（SO 当前输出 null）
 */
data class SupportCardPartner(
    @SerializedName("partner_id") val partnerId: Int = 0,
    @SerializedName("support_position") val supportPosition: Int = 0,
    @SerializedName("support_card_id") val supportCardId: Int = 0,
    @SerializedName("current_bond") val currentBond: Int = 0,
    @SerializedName("is_shining") val isShining: Boolean = false,
    @SerializedName("is_unique_active") val isUniqueActive: Boolean = false,
    @SerializedName("bond_threshold") val bondThreshold: Int = 0,
    @SerializedName("support_card_type") val supportCardType: Int = 0,
    @SerializedName("is_tips_event") val isTipsEvent: Boolean = false,
    @SerializedName("partner_type") val partnerType: Int = 0,
    @SerializedName("name") val name: String? = null,
)
