package com.xf8410.ramen.advisor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 预加载卡DB缓存
        CardDatabase.loadFromCache(this)
        val cardDb = CardDatabase.getInstance()
        val cardDbInfo = if (cardDb != null) "缓存${cardDb.size()}张" else "无缓存"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(32, 48 + bars.top, 32, 32 + bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }

        // 标题
        root.addView(TextView(this).apply {
            text = "拉面杯训练决策器"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        })

        // 卡DB状态
        root.addView(TextView(this).apply {
            text = "支援卡DB: $cardDbInfo\n(启动浮窗后自动从SO拉取最新)"
            textSize = 13f
            setPadding(0, 0, 0, 16)
        })

        // 从SO拉取卡DB
        root.addView(Button(this).apply {
            text = "从SO拉取支援卡DB"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "拉取中...", Toast.LENGTH_SHORT).show()
                Thread {
                    val count = CardDatabase.fetchFromSo(this@MainActivity)
                    runOnUiThread {
                        if (count > 0) {
                            Toast.makeText(this@MainActivity, "拉取成功: ${count}张卡", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "拉取失败：SO未连接", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        })

        // 悬浮窗权限
        root.addView(Button(this).apply {
            text = "悬浮窗权限设置"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${packageName}")
                    ))
                } else {
                    Toast.makeText(this@MainActivity, "已有权限", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // 启动浮窗
        root.addView(Button(this).apply {
            text = "启动训练建议浮窗"
            setOnClickListener {
                FloatingWindowService.start(this@MainActivity)
                finish()
            }
        })

        // 上游信息
        root.addView(TextView(this).apply {
            text = """

                -----
                训练逻辑: hzyhhzy/UmaAi HandwrittenLogic.cpp 移植
                配卡: 3速1耐1智1友人
                权重: 速10 耐5 力3 根2 智6 (动态)
                
                数据来源: hlpatch SO 插件
                https://github.com/xf8410/hlpatch
            """.trimIndent()
            textSize = 12f
            setPadding(0, 32, 0, 0)
        })

        setContentView(root)
    }
}
