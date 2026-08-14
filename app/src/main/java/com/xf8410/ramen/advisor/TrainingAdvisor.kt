package com.xf8410.ramen.advisor

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 训练建议器 — 基于hzyhhzy/UmaAi手写逻辑，移植为Kotlin
 *
 * 原始来源：UmaSimulator/NeuralNet/HandwrittenLogic.cpp
 * 原作者：hzyhhzy
 *
 * 适配改动：
 * 1. statusWeights 改为 3速1耐1智1友人 配卡
 * 2. 移除机体杯专属逻辑（mechaLv, overdrive）
 * 3. 数据源从内部Game类改为 SO /summary 的 SummaryResponse
 * 4. 加拉面杯地区选择预设
 *
 * 核心估值公式（与原版一致）：
 * - 属性增益 = Σ statusWeights[sta] * softFunction(gain - remain)
 * - 羁绊增益 = jibanAdd * jibanValue (bond < 80 时)
 * - 体力估值 = vitalFactor * (vitalEvalAfter - vitalEvalBefore)
 * - 失败惩罚 = failRate% * failValue
 */
object TrainingAdvisor {

    // ===== 卡组配置：3速1耐1智1友人 =====
    // statusWeights: 速, 耐, 力, 根, 智
    // 基础权重：3速卡速度最高，1耐1智中等，力根无卡但仍需练
    private val STATUS_WEIGHTS_BASE = doubleArrayOf(10.0, 5.0, 3.0, 2.0, 6.0)

    // 属性上限：速2200, 耐力1700, 力量1700, 根性1700, 智力1800
    private val STATUS_CAP = intArrayOf(2200, 1700, 1700, 1700, 1800)

    /**
     * 动态权重：属性越接近上限，权重越低（速度溢出时转移给力根等）
     * 使用对数衰减：weight = base * (1 - current/cap) * 某系数
     * 但保证不低于基础值的20%，避免完全不练
     */
    private fun dynamicWeights(fiveStatus: IntArray): DoubleArray {
        val weights = DoubleArray(5)
        var totalReduction = 0.0  // 被削减的权重总量
        var totalCapacity = 0.0   // 还有空间的属性的总权重

        for (i in 0..4) {
            val ratio = fiveStatus[i].toDouble() / STATUS_CAP[i]
            if (ratio >= 0.85) {
                // 接近上限：权重大幅削减
                val factor = max(0.2, 1.0 - (ratio - 0.85) * 4.0)  // 85%→1.0, 100%→0.2
                weights[i] = STATUS_WEIGHTS_BASE[i] * factor
                totalReduction += STATUS_WEIGHTS_BASE[i] * (1.0 - factor)
            } else {
                weights[i] = STATUS_WEIGHTS_BASE[i]
                totalCapacity += STATUS_WEIGHTS_BASE[i]
            }
        }

        // 把削减的权重按比例转移给还有空间的属性
        if (totalCapacity > 0 && totalReduction > 0) {
            for (i in 0..4) {
                val ratio = fiveStatus[i].toDouble() / STATUS_CAP[i]
                if (ratio < 0.85) {
                    weights[i] += totalReduction * (STATUS_WEIGHTS_BASE[i] / totalCapacity)
                }
            }
        }

        return weights
    }

    // ===== 估值参数（与hzyhhzy原版一致）=====
    private const val JIBAN_VALUE = 12.0          // 每点羁绊的估值
    private const val VITAL_FACTOR_START = 3.5    // 前期体力因子
    private const val VITAL_FACTOR_END = 7.0      // 后期体力因子
    private const val VITAL_SCALE_TRAINING = 1.0  // 训练体力权重
    private const val RESERVE_STATUS_FACTOR = 40.0 // 控属性保留空间

    private const val SMALL_FAIL_VALUE = -150.0
    private const val BIG_FAIL_VALUE = -500.0
    private const val OUTGOING_BONUS_IF_NOT_FULL_MOTIVATION = 200.0
    private const val RACE_BONUS = 150.0

    // 训练类型常量
    private const val TRA_SPEED = 0
    private const val TRA_STAMINA = 1
    private const val TRA_POWER = 2
    private const val TRA_GUTS = 3
    private const val TRA_WISDOM = 4
    private const val TRA_REST = -1
    private const val TRA_OUTGOING = -2
    private const val TRA_RACE = -3

    private val TRA_NAMES = arrayOf("速度", "耐力", "力量", "根性", "智力")
    private val CMD_TO_TRA = mapOf(101 to 0, 102 to 1, 103 to 2, 104 to 3, 105 to 4)

    // 总回合数（拉面杯与大师杯一致：78回合，0-77）
    private const val TOTAL_TURN = 78

    // ===== 拉面杯地区选择预设 =====
    // 用户指定：
    // 第一年：速耐智拉面
    // 第二年：2,3,5拉面
    // 第三年：速耐智拉面
    // URA：中间的拉面
    data class RamenPreset(
        val year1: List<Int>,   // 第一年地区选择
        val year2: List<Int>,   // 第二年
        val year3: List<Int>,   // 第三年
        val ura: Int,           // URA选择
    )

    // 地区ID到名称的映射（与MDB single_mode_14_region_feeling对应）
    private val REGION_NAMES = mapOf(
        1 to "札幌", 2 to "函館", 3 to "新潟", 4 to "福島", 5 to "東京",
        6 to "中山", 7 to "京都", 8 to "阪神", 9 to "大井", 10 to "川崎",
    )

    // 速耐智 = 速度训练(101)对应地区, 耐力(102), 智力(105)
    // 这里用户说的"速耐智拉面"是指选择产出速/耐/智素材的地区
    // 具体地区ID需要跟MDB对照，先用训练类型映射
    val RAMEN_PRESET = RamenPreset(
        year1 = listOf(1, 2, 5),   // 速耐智
        year2 = listOf(2, 3, 5),   // 用户指定 2,3,5
        year3 = listOf(1, 2, 5),   // 速耐智
        ura = 2,                   // 中间
    )

    /**
     * 给出训练建议
     *
     * 输入：SO /summary 返回的 SummaryResponse
     * 输出：推荐训练 + 各选项估值
     */
    fun advise(summary: SummaryResponse): TrainingRecommendation {

        val chara = summary.charaInfo
        val trainings = summary.trainings ?: emptyList()
        val turn = summary.turnNum

        // 五维属性和上限
        val fiveStatus = intArrayOf(
            chara?.speed ?: 0,
            chara?.stamina ?: 0,
            chara?.power ?: 0,
            chara?.guts ?: 0,
            chara?.wiz ?: 0
        )
        val fiveStatusLimit = intArrayOf(2200, 1700, 1700, 1700, 1800)

        // 动态权重：速度快溢出时降权，转移给力根等
        val statusWeights = dynamicWeights(fiveStatus)

        // 体力
        val vital = chara?.vital ?: 0
        val maxVital = chara?.maxVital ?: 100
        val motivation = chara?.motivation ?: 3

        // 体力因子（随回合数线性增长）
        val vitalFactor = VITAL_FACTOR_START +
                (turn.toDouble() / TOTAL_TURN) * (VITAL_FACTOR_END - VITAL_FACTOR_START)

        // 最大等效体力
        val maxVitalEq = calculateMaxVitalEquivalent(turn, maxVital, summary)
        val vitalEvalBefore = vitalEvaluation(min(maxVitalEq, vital), maxVital)

        // 控属性保留空间
        val remainTurn = TOTAL_TURN - turn - 1
        val reserve = RESERVE_STATUS_FACTOR * remainTurn * (1.0 - remainTurn.toDouble() / (TOTAL_TURN * 2))
        val reserveInvX2 = 1.0 / (2 * reserve)

        // 最终属性加成估计（URA+最终事件）
        var finalBonus = 45.0 + 30.0 // ura3 + 最终事件
        if (remainTurn >= 1) finalBonus += 20.0 // ura2
        if (remainTurn >= 2) finalBonus += 20.0 // ura1

        // 各属性剩余空间
        val remain = DoubleArray(5)
        for (i in 0 until 5) {
            remain[i] = fiveStatusLimit[i] - fiveStatus[i] - finalBonus
        }

        // 评估各选项
        val options = mutableListOf<TrainingOption>()

        // 休息/外出
        val friendCardAvailable = trainings.any { t ->
            t.partners?.any { it.supportCardType == 2 } == true
        }

        val isXiahesu = (turn in 36..39) || (turn in 60..63)
        val isFriendOutgoingAvailable = friendCardAvailable && !isXiahesu

        val restVitalGain = if (isFriendOutgoingAvailable) 50 else if (isXiahesu) 40 else 50
        val restVitalAfter = min(maxVitalEq, restVitalGain + vital)
        val restValue = vitalFactor * (vitalEvaluation(restVitalAfter, maxVital) - vitalEvalBefore)
        val addMotivation = motivation < 5 && isFriendOutgoingAvailable
        val restFinalValue = restValue + if (addMotivation) OUTGOING_BONUS_IF_NOT_FULL_MOTIVATION else 0.0

        options.add(TrainingOption(
            action = if (isFriendOutgoingAvailable) "外出(友人)" else "休息",
            value = restFinalValue,
            isBest = false,
            detail = "体力 $vital→$restVitalAfter" + if (addMotivation) " +干劲" else "",
        ))

        // 各训练选项
        for (tra in 0 until 5) {
            // 找对应的训练数据
            val cmdId = when (tra) {
                0 -> 101; 1 -> 102; 2 -> 103; 3 -> 104; 4 -> 105; else -> continue
            }
            val training = trainings.find { it.commandId == cmdId } ?: continue
            if (training.isEnable != 1) {
                options.add(TrainingOption(
                    action = TRA_NAMES[tra],
                    value = Double.NEGATIVE_INFINITY,
                    isBest = false,
                    detail = "不可用",
                ))
                continue
            }

            // 属性增益估值
            val gains = intArrayOf(training.speed, training.stamina, training.power, training.guts, training.wiz)
            val skillPtGain = 0 // SO不直接输出pt gain，暂设0

            var statusValue = 0.0
            for (sta in 0 until 5) {
                val s0 = statusSoftFunction(-remain[sta], reserve, reserveInvX2)
                val s1 = statusSoftFunction(gains[sta] - remain[sta], reserve, reserveInvX2)
                statusValue += statusWeights[sta] * (s1 - s0)
            }

            // 羁绊估值
            var bondValue = 0.0
            val partners = training.partners ?: emptyList()
            val cardPartners = partners.filter { it.partnerType == 1 }
            val hintPartners = cardPartners.filter { it.isTipsEvent }
            val hintProb = if (hintPartners.isNotEmpty()) 1.0 / hintPartners.size else 0.0

            for (p in cardPartners) {
                if (p.supportCardType == 2) {
                    // 友人卡
                    bondValue += when {
                        p.currentBond < 30 -> 150.0
                        p.currentBond < 60 -> 100.0
                        else -> 40.0
                    }
                } else if (p.currentBond < 80) {
                    // 普通卡（bond < 80时有价值）
                    var jibanAdd = 7.0
                    if (p.isTipsEvent) jibanAdd += 5.0 * hintProb
                    jibanAdd = min(80.0 - p.currentBond, jibanAdd)
                    bondValue += jibanAdd * JIBAN_VALUE
                }

                // 灵感估值
                if (p.isTipsEvent) {
                    val hintBonus = 1.6 * statusWeights.sum()
                    bondValue += hintBonus * hintProb
                }
            }

            // 体力消耗估值
            // SO训练数据没有直接的vitalChange，用经验值：训练约-15~-20体力
            val vitalCost = estimateVitalCost(tra, motivation)
            val vitalAfterTrain = min(maxVitalEq, vital + vitalCost)
            val vitalValue = VITAL_SCALE_TRAINING * vitalFactor *
                    (vitalEvaluation(vitalAfterTrain, maxVital) - vitalEvalBefore)

            // 失败率惩罚
            val failRate = training.failureRate
            var value = statusValue + bondValue + vitalValue

            if (failRate > 0) {
                val bigFailProb = if (failRate < 20) 0.0 else failRate.toDouble()
                val failValueAvg = 0.01 * bigFailProb * BIG_FAIL_VALUE +
                        (1 - 0.01 * bigFailProb) * SMALL_FAIL_VALUE
                value = 0.01 * failRate * failValueAvg + (1 - 0.01 * failRate) * value
            }

            val shiningCount = cardPartners.count { it.isShining }

            options.add(TrainingOption(
                action = TRA_NAMES[tra],
                value = value,
                isBest = false,
                detail = buildString {
                    append("+" + gains.joinToString("/") { if (it > 0) it.toString() else "·" })
                    if (shiningCount > 0) append(" ★$shiningCount")
                    if (failRate > 0) append(" 失败${failRate}%")
                    append(" 体力$vital→${vitalAfterTrain}")
                },
            ))
        }

        // 找最大值
        val bestOption = options.maxByOrNull { it.value }
        if (bestOption != null) bestOption.isBest = true

        // 拉面选择建议
        val ramenAdvice = getRamenAdvice(turn)

        return TrainingRecommendation(
            turn = turn,
            bestAction = bestOption?.action ?: "未知",
            options = options.sortedByDescending { it.value },
            ramenAdvice = ramenAdvice,
            dynamicWeights = statusWeights,
        )
    }

    /**
     * 控属性分段函数
     * 当属性接近上限时，边际收益递减
     */
    private fun statusSoftFunction(x: Double, reserve: Double, reserveInvX2: Double): Double {
        if (x >= 0) return 0.0
        if (x > -reserve) return -x * x * reserveInvX2
        return x + 0.5 * reserve
    }

    /**
     * 体力估值函数
     * 体力越低边际价值越高
     */
    private fun vitalEvaluation(vital: Int, maxVital: Int): Double {
        if (vital <= 50) return 2.0 * vital
        if (vital <= 70) return 1.5 * (vital - 50) + vitalEvaluation(50, maxVital)
        if (vital <= maxVital) return 1.0 * (vital - 70) + vitalEvaluation(70, maxVital)
        return vitalEvaluation(maxVital, maxVital)
    }

    /**
     * 计算最大等效体力
     * 考虑后续回合能回复的体力
     */
    private fun calculateMaxVitalEquivalent(turn: Int, maxVital: Int, summary: SummaryResponse): Int {
        if (turn >= 76) return 0
        if (turn > 71) return 10  // URA期间可以吃菜
        if (turn == 71) return 30

        // 估算后续非比赛回合数
        var nonRaceTurn = 0
        for (i in turn + 1..71) {
            nonRaceTurn++
            if (nonRaceTurn >= 6) break
        }

        var maxVitalEq = 30 + 15 * nonRaceTurn
        if (maxVitalEq > maxVital) maxVitalEq = maxVital
        return maxVitalEq
    }

    /**
     * 估算训练体力消耗
     * 不同训练消耗不同，受干劲影响
     */
    private fun estimateVitalCost(tra: Int, motivation: Int): Int {
        // 基础消耗：速耐力约-20, 根性-18, 智力-15
        val baseCost = when (tra) {
            0 -> -20  // 速度
            1 -> -20  // 耐力
            2 -> -20  // 力量
            3 -> -18  // 根性
            4 -> -15  // 智力
            else -> -20
        }
        // 干劲影响：高干劲消耗略少
        val motivationAdj = (motivation - 3) * 1
        return baseCost + motivationAdj
    }

    /**
     * 拉面选择建议
     */
    private fun getRamenAdvice(turn: Int): String {
        return when {
            turn <= 1 -> "第一年: 选${RAMEN_PRESET.year1.joinToString("·") { REGION_NAMES[it] ?: "?$it" }}地区"
            turn in 2..23 -> "第二年: 选${RAMEN_PRESET.year2.joinToString("·") { REGION_NAMES[it] ?: "?$it" }}地区"
            turn in 24..25 -> "第二年: 选${RAMEN_PRESET.year2.joinToString("·") { REGION_NAMES[it] ?: "?$it" }}地区"
            turn in 26..47 -> "第三年: 选${RAMEN_PRESET.year3.joinToString("·") { REGION_NAMES[it] ?: "?$it" }}地区"
            turn in 48..49 -> "第三年: 选${RAMEN_PRESET.year3.joinToString("·") { REGION_NAMES[it] ?: "?$it" }}地区"
            turn in 50..71 -> "URA: 选${REGION_NAMES[RAMEN_PRESET.ura] ?: "?"}地区"
            else -> ""
        }
    }

    // ===== 数据类 =====

    data class TrainingRecommendation(
        val turn: Int,
        val bestAction: String,
        val options: List<TrainingOption>,
        val ramenAdvice: String,
        val dynamicWeights: DoubleArray = doubleArrayOf(10.0, 5.0, 3.0, 2.0, 6.0),
    ) {
        fun toDisplayText(): String {
            val sb = StringBuilder()
            sb.appendLine("── 训练建议 ──")
            sb.appendLine("推荐: $bestAction")
            if (ramenAdvice.isNotEmpty()) {
                sb.appendLine(ramenAdvice)
            }
            // 显示当前动态权重
            val w = dynamicWeights
            sb.appendLine("权重: 速${w[0].toInt()} 耐${w[1].toInt()} 力${w[2].toInt()} 根${w[3].toInt()} 智${w[4].toInt()}")
            sb.appendLine("── 各选项估值 ──")
            for (opt in options) {
                val mark = if (opt.isBest) "▶" else " "
                val valueStr = if (opt.value == Double.NEGATIVE_INFINITY) "N/A" else String.format("%.0f", opt.value)
                sb.appendLine("$mark ${opt.action.padEnd(6)} $valueStr  ${opt.detail}")
            }
            return sb.toString().trimEnd()
        }
    }

    data class TrainingOption(
        val action: String,
        val value: Double,
        var isBest: Boolean,
        val detail: String,
    )
}
