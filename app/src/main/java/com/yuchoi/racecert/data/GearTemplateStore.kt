package com.yuchoi.racecert.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 종목별 준비물 기본 템플릿. 최초 실행 시 내장 기본값으로 시드되고,
 * 이후 사용자가 새 항목을 "기본 준비물에 추가"할 때마다 파일에 저장돼 이어진다.
 * 새 기록의 준비물 탭을 처음 열 때 이 템플릿으로 초기화된다(레코드별 사본).
 */
object GearTemplateStore {

    private lateinit var appContext: Context
    private val file: File get() = File(appContext.filesDir, "gear_templates.json")

    private val templates = LinkedHashMap<RaceType, MutableList<GearItem>>()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        if (file.exists()) load() else seedDefaults()
        // 파일 로드 후에도 아직 없는 종목(예: 업데이트로 새로 생긴 RaceType)은 기본값으로 보강
        RaceType.entries.forEach { type ->
            if (type !in templates) templates[type] = defaultFor(type)
        }
        persist()
    }

    /** 종목별 기본 준비물 (레코드에 붙여 쓸 새 사본으로 반환) */
    fun templateFor(type: RaceType): List<GearItem> =
        templates[type].orEmpty().map { it.copy(id = UUID.randomUUID().toString(), checked = false) }

    /** 이미 그 종목 기본 템플릿에 같은 섹션·이름 항목이 있는지 */
    fun existsInTemplate(type: RaceType, section: String, label: String): Boolean =
        templates[type]?.any { it.section == section && it.label.equals(label, ignoreCase = true) } == true

    /** 새 항목을 선택한 종목들의 기본 템플릿에 추가한다 */
    fun addToTemplates(types: Set<RaceType>, section: String, label: String) {
        types.forEach { type ->
            val list = templates.getOrPut(type) { mutableListOf() }
            if (list.none { it.section == section && it.label.equals(label, ignoreCase = true) }) {
                list.add(GearItem(id = UUID.randomUUID().toString(), section = section, label = label))
            }
        }
        persist()
    }

    private fun item(section: String, label: String) =
        GearItem(id = UUID.randomUUID().toString(), section = section, label = label)

    private fun defaultFor(type: RaceType): MutableList<GearItem> = when (type) {
        RaceType.MARATHON, RaceType.TRAILRUN -> defaultRunningList()
        RaceType.TRIATHLON -> defaultTriathlonList()
        RaceType.GRANFONDO -> defaultGranfondoList()
        RaceType.OTHER -> mutableListOf()
    }

    private fun seedDefaults() {
        RaceType.entries.forEach { templates[it] = defaultFor(it) }
    }

    private fun defaultRunningList(): MutableList<GearItem> = mutableListOf(
        item(GearSections.WEAR, "상의 바람막이 티 - 아디다스저지"),
        item(GearSections.WEAR, "하의 긴바지 - 나이키"),
        item(GearSections.WEAR, "니플 반창고"),
        item(GearSections.WEAR, "배번 - 테그"),
        item(GearSections.WEAR, "팔토시"),
        item(GearSections.WEAR, "종아리보호"),
        item(GearSections.WEAR, "테이핑"),
        item(GearSections.WEAR, "크록스"),
        item(GearSections.WEAR, "바셀린"),
        item(GearSections.WEAR, "심박계"),
        item(GearSections.WEAR, "워치"),
        item(GearSections.BAG, "썬글라스"),
        item(GearSections.BAG, "러닝모자"),
        item(GearSections.BAG, "우의"),
        item(GearSections.BAG, "러닝화"),
        item(GearSections.BAG, "장갑"),
        item(GearSections.BAG, "러닝벨트 / 에너지젤 / 쥐약 / 리모컨"),
        item(GearSections.BAG, "썬크림"),
        item(GearSections.BAG, "이어폰"),
        item(GearSections.BAG, "핫팩"),
        item(GearSections.BAG, "물 500 / 커피 / 포카리"),
        item(GearSections.BAG, "테이핑 / 테이핑가위"),
        item(GearSections.AFTER, "갈아입을옷 (상의 반팔 - 바지)"),
        item(GearSections.AFTER, "다른 모자"),
        item(GearSections.AFTER, "수건 (코인수건)"),
        item(GearSections.AFTER, "파스"),
        item(GearSections.AFTER, "데오드란트"),
        item(GearSections.AFTER, "비닐봉투 2개"),
        item(GearSections.AFTER, "타이레놀 / 시알"),
        item(GearSections.AFTER, "휴지"),
        item(GearSections.AFTER, "이불"),
        item(GearSections.AFTER, "탁센 약먹기"),
        item(GearSections.AFTER, "씨알 진통제"),
        item(GearSections.AFTER, "발마사지"),
    )

    private fun defaultTriathlonList(): MutableList<GearItem> = mutableListOf(
        item(GearSections.WEAR, "수영모"),
        item(GearSections.WEAR, "수경"),
        item(GearSections.WEAR, "트라이슈트 / 웻슈트"),
        item(GearSections.WEAR, "배번 (바디마킹)"),
        item(GearSections.WEAR, "워치"),
        item(GearSections.WEAR, "심박계"),
        item(GearSections.BAG, "자전거 헬멧"),
        item(GearSections.BAG, "사이클 장갑"),
        item(GearSections.BAG, "사이클화 / 러닝화"),
        item(GearSections.BAG, "고글"),
        item(GearSections.BAG, "에너지젤 / 보급"),
        item(GearSections.BAG, "물통"),
        item(GearSections.BAG, "썬크림"),
        item(GearSections.AFTER, "갈아입을옷"),
        item(GearSections.AFTER, "수건"),
        item(GearSections.AFTER, "파스"),
        item(GearSections.AFTER, "휴지"),
        item(GearSections.AFTER, "비닐봉투 2개"),
    )

    private fun defaultGranfondoList(): MutableList<GearItem> = mutableListOf(
        item(GearSections.WEAR, "헬멧"),
        item(GearSections.WEAR, "사이클 저지"),
        item(GearSections.WEAR, "사이클 빕숏"),
        item(GearSections.WEAR, "사이클 장갑"),
        item(GearSections.WEAR, "사이클화"),
        item(GearSections.WEAR, "고글"),
        item(GearSections.WEAR, "워치"),
        item(GearSections.BAG, "물통"),
        item(GearSections.BAG, "에너지젤 / 보급"),
        item(GearSections.BAG, "펑크 수리 키트 (예비튜브/CO2)"),
        item(GearSections.BAG, "멀티툴"),
        item(GearSections.BAG, "썬크림"),
        item(GearSections.BAG, "우의"),
        item(GearSections.AFTER, "갈아입을옷"),
        item(GearSections.AFTER, "수건"),
        item(GearSections.AFTER, "파스"),
        item(GearSections.AFTER, "휴지"),
    )

    private fun load() {
        runCatching {
            val obj = JSONObject(file.readText())
            RaceType.entries.forEach { type ->
                val arr = obj.optJSONArray(type.name) ?: return@forEach
                val list = mutableListOf<GearItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        GearItem(
                            id = UUID.randomUUID().toString(),
                            section = o.optString("section"),
                            label = o.optString("label"),
                        ),
                    )
                }
                templates[type] = list
            }
        }.onFailure { seedDefaults() }
    }

    private fun persist() {
        val obj = JSONObject()
        templates.forEach { (type, list) ->
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("section", it.section).put("label", it.label)) }
            obj.put(type.name, arr)
        }
        runCatching { file.writeText(obj.toString()) }
    }
}
