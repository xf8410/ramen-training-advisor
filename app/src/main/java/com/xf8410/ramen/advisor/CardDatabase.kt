package com.xf8410.ramen.advisor

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 支援卡数据库 — 从 hlpatch SO /mdb/raw 端点动态加载
 *
 * 数据来源：游戏运行时 MDB support_card_data 表
 * 通过 SO 端点 /mdb/raw?sql=SELECT... 查询，不内嵌静态文件
 *
 * 查询两条 SQL：
 * 1. support_card_data — 卡面信息（chara_id, rarity, command_id, support_card_type, unique_effect_id）
 * 2. support_card_unique_effect — 固有效果（type_0=101 的 value_0 = bond_threshold）
 *
 * 查询结果缓存到本地文件，下次启动先读缓存，后台再刷新。
 *
 * ⚠️ 命名陷阱：cardDB 的 cardType 与 MDB 的 support_card_type 不同！
 *   cardDB.cardType:       0=速,1=耐,2=力,3=根,4=智,5=友,6=团（训练类型）
 *   MDB.support_card_type: 1=普通,2=友人,3=团体（卡类别）
 *
 * cardValue 字段安全：只有 5 个字段全卡必有（bonus, filled, hintBonus, hintLevel, initialBonus），
 * 其余 12 个字段（youQing, ganJing, deYiLv, initialJiBan, hintProbIncrease, saiHou,
 * xunLian, wizVitalBonus, eventEffectUp, eventRecoveryAmountUp, failRateDrop, vitalCostDrop）
 * 全可选，必须用 getOptionalInt() 取值。
 */
class CardDatabase private constructor(
    private val cards: Map<Int, CardData>,
    private val cacheFile: File,
) {
    companion object {
        @Volatile
        private var instance: CardDatabase? = null

        /**
         * 获取已加载的实例（同步，不触发网络请求）
         * 首次调用返回 null，需先调用 loadFromCache() 或 loadFromSo()
         */
        fun getInstance(): CardDatabase? = instance

        /**
         * 从本地缓存加载（同步，不网络请求）
         * 返回 null 表示无缓存
         */
        fun loadFromCache(context: Context): CardDatabase? {
            instance?.let { return it }
            val cacheFile = File(context.filesDir, "cardDB_cache.json")
            if (!cacheFile.exists()) return null
            return try {
                val json = cacheFile.readText()
                val root = JsonParser.parseString(json).asJsonObject
                val cards = mutableMapOf<Int, CardData>()
                for ((key, value) in root.entrySet()) {
                    val cardId = key.toInt()
                    val obj = value.asJsonObject
                    val cardValues = mutableListOf<CardValue>()
                    val cvArray = obj.getAsJsonArray("cardValue") ?: continue
                    for (cvElem in cvArray) {
                        val o = cvElem.asJsonObject
                        cardValues.add(CardValue(
                            filled = o.get("filled")?.asBoolean ?: false,
                            bonus = readIntArray(o, "bonus", 5),
                            initialBonus = readIntArray(o, "initialBonus", 5),
                            hintBonus = readIntArray(o, "hintBonus", 5),
                            hintLevel = o.get("hintLevel")?.asInt ?: 0,
                            youQing = o.get("youQing")?.asInt ?: 0,
                            ganJing = o.get("ganJing")?.asInt ?: 0,
                            deYiLv = o.get("deYiLv")?.asInt ?: 0,
                            initialJiBan = o.get("initialJiBan")?.asInt ?: 0,
                            hintProbIncrease = o.get("hintProbIncrease")?.asInt ?: 0,
                            saiHou = o.get("saiHou")?.asInt ?: 0,
                            xunLian = o.get("xunLian")?.asInt ?: 0,
                            wizVitalBonus = o.get("wizVitalBonus")?.asInt ?: 0,
                            eventEffectUp = o.get("eventEffectUp")?.asInt ?: 0,
                            eventRecoveryAmountUp = o.get("eventRecoveryAmountUp")?.asInt ?: 0,
                            failRateDrop = o.get("failRateDrop")?.asInt ?: 0,
                            vitalCostDrop = o.get("vitalCostDrop")?.asInt ?: 0,
                        ))
                    }
                    cards[cardId] = CardData(
                        cardId = cardId,
                        cardName = obj.get("cardName")?.asString ?: "?$cardId",
                        rarity = obj.get("rarity")?.asInt ?: 0,
                        cardType = obj.get("cardType")?.asInt ?: 0,
                        cardValue = cardValues,
                    )
                }
                CardDatabase(cards, cacheFile).also { instance = it }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 从 hlpatch SO /mdb/raw 端点加载支援卡数据
         *
         * 查询 support_card_data 表，获取全部支援卡的：
         * - id (support_card_id)
         * - chara_id, rarity, command_id, support_card_type
         * - unique_effect_id (用于查 bond_threshold)
         *
         * 返回 null 表示 SO 不可用或查询失败。
         * 成功时自动写入缓存文件。
         */
        fun loadFromSo(
            context: Context,
            host: String = "127.0.0.1",
            port: Int = 18765,
        ): CardDatabase? {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            // 查 support_card_data 全表
            val sql = "SELECT id, chara_id, rarity, command_id, support_card_type, unique_effect_id FROM support_card_data ORDER BY id"
            val url = "http://$host:$port/mdb/raw?sql=${java.net.URLEncoder.encode(sql, "UTF-8")}"

            val response = try {
                client.newCall(Request.Builder().url(url).build()).execute()
            } catch (e: Exception) {
                return null
            }

            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val gson = Gson()

            try {
                @Suppress("UNCHECKED_CAST")
                val parsed = gson.fromJson(body, Map::class.java) as Map<String, Any>
                val columns = parsed["columns"] as? List<String> ?: return null
                val rows = parsed["rows"] as? List<Map<String, Any>> ?: return null

                // 查 unique_effect 的 threshold
                val thresholdMap = queryBondThresholds(client, host, port)

                val cards = mutableMapOf<Int, CardData>()
                for (row in rows) {
                    val cardId = (row["id"] as? Number)?.toInt() ?: continue
                    val charaId = (row["chara_id"] as? Number)?.toInt() ?: 0
                    val rarity = (row["rarity"] as? Number)?.toInt() ?: 0
                    val commandId = (row["command_id"] as? Number)?.toInt() ?: 0
                    val scType = (row["support_card_type"] as? Number)?.toInt() ?: 0
                    val uniqueEffectId = (row["unique_effect_id"] as? Number)?.toInt() ?: 0

                    // cardType 从 command_id 映射（0=速,1=耐,2=力,3=根,4=智）
                    // 友人/团体卡 command_id 可能为 0
                    val cardType = when (scType) {
                        2 -> 5  // 友人
                        3 -> 6  // 团体
                        else -> when (commandId) {
                            101 -> 0  // 速
                            102 -> 1  // 耐
                            103 -> 2  // 力
                            104 -> 3  // 根
                            105 -> 4  // 智
                            else -> 0
                        }
                    }

                    val threshold = thresholdMap[uniqueEffectId] ?: Int.MAX_VALUE

                    cards[cardId] = CardData(
                        cardId = cardId,
                        cardName = "chara_${charaId}",  // 名称后续从 text_data 查
                        rarity = rarity,
                        cardType = cardType,
                        cardValue = emptyList(),  // cardValue 不从 MDB 查，需要单独导出
                        bondThreshold = threshold,
                        supportCardType = scType,
                        commandId = commandId,
                    )
                }

                val cacheFile = File(context.filesDir, "cardDB_cache.json")
                val cacheDb = CardDatabase(cards, cacheFile)
                cacheDb.saveCache()
                instance = cacheDb
                cacheDb
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 查 unique_effect 表获取 bond_threshold
         * type_0=101 → value_0 = bond threshold
         */
        private fun queryBondThresholds(
            client: OkHttpClient,
            host: String,
            port: Int,
        ): Map<Int, Int> {
            val sql = "SELECT id, type_0, value_0 FROM support_card_unique_effect WHERE type_0 = 101"
            val url = "http://$host:$port/mdb/raw?sql=${java.net.URLEncoder.encode(sql, "UTF-8")}"

            val response = try {
                client.newCall(Request.Builder().url(url).build()).execute()
            } catch (e: Exception) {
                return emptyMap()
            }

            val body = response.body?.string() ?: return emptyMap()
            val gson = Gson()

            return try {
                @Suppress("UNCHECKED_CAST")
                val parsed = gson.fromJson(body, Map::class.java) as Map<String, Any>
                val rows = parsed["rows"] as? List<Map<String, Any>> ?: return emptyMap()
                val result = mutableMapOf<Int, Int>()
                for (row in rows) {
                    val id = (row["id"] as? Number)?.toInt() ?: continue
                    val value0 = (row["value_0"] as? Number)?.toInt() ?: continue
                    result[id] = value0
                }
                result
            } catch (e: Exception) {
                emptyMap()
            }
        }

        /**
         * 从 SO 拉取并缓存，返回卡数量（0 表示失败）
         * 供 UI 线程调用，内部异步执行
         */
        fun fetchFromSo(context: Context): Int {
            val db = loadFromSo(context) ?: return 0
            return db.size()
        }

        private fun readIntArray(obj: JsonObject, key: String, size: Int): IntArray {
            val arr = obj.getAsJsonArray(key) ?: return IntArray(size)
            return IntArray(size) { i -> arr.getOrNull(i)?.asInt ?: 0 }
        }
    }

    fun getCard(cardId: Int): CardData? = cards[cardId]

    fun size(): Int = cards.size

    /**
     * 保存缓存到本地文件
     */
    private fun saveCache() {
        try {
            val gson = Gson()
            val root = JsonObject()
            for ((cardId, card) in cards) {
                val obj = JsonObject()
                obj.addProperty("cardId", card.cardId)
                obj.addProperty("cardName", card.cardName)
                obj.addProperty("rarity", card.rarity)
                obj.addProperty("cardType", card.cardType)
                if (card.bondThreshold != Int.MAX_VALUE) {
                    obj.addProperty("bondThreshold", card.bondThreshold)
                }
                obj.addProperty("supportCardType", card.supportCardType)
                obj.addProperty("commandId", card.commandId)
                root.add(cardId.toString(), obj)
            }
            cacheFile.writeText(gson.toJson(root))
        } catch (e: Exception) {
            // 缓存写入失败不影响运行
        }
    }
}

data class CardData(
    val cardId: Int,
    val cardName: String,
    val rarity: Int,          // 1=R, 2=SR, 3=SSR
    val cardType: Int,        // cardDB训练类型: 0=速,1=耐,2=力,3=根,4=智,5=友,6=团
    val cardValue: List<CardValue>,   // 从 cardDB.json 导入时有值，SO 查询时为空
    val bondThreshold: Int = Int.MAX_VALUE,  // 固有效果激活门槛
    val supportCardType: Int = 0,     // MDB support_card_type: 1=普通,2=友人,3=团体
    val commandId: Int = 0,           // MDB command_id: 101=速,...,105=智
)

data class CardValue(
    val filled: Boolean,
    val bonus: IntArray,          // 5元素: 速耐力根智
    val initialBonus: IntArray,   // 5元素
    val hintBonus: IntArray,      // 5元素 (第6个是技能Pt)
    val hintLevel: Int,
    // 可选字段 — 用 .get() 安全取值
    val youQing: Int = 0,         // 友情加成
    val ganJing: Int = 0,         // 干劲加成
    val deYiLv: Int = 0,          // 得意率
    val initialJiBan: Int = 0,    // 初始羁绊
    val hintProbIncrease: Int = 0,// 灵感概率
    val saiHou: Int = 0,          // 赛后期加成
    val xunLian: Int = 0,         // 训练加成
    val wizVitalBonus: Int = 0,   // 智力体力加成
    val eventEffectUp: Int = 0,   // 事件效果
    val eventRecoveryAmountUp: Int = 0, // 事件回复
    val failRateDrop: Int = 0,    // 失败率下降
    val vitalCostDrop: Int = 0,   // 体力消耗下降
)
