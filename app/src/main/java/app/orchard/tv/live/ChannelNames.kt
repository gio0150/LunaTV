package app.orchard.tv.live

/**
 * IPTV playlists usually carry English transliterations for Chinese broadcasters. OneTV shows
 * the names people actually use, so Luna translates the common channels and their groups while
 * leaving any custom or already-Chinese playlist untouched.
 */
object ChannelNames {
    private val names = mapOf(
        "ABN China" to "ABN 中国",
        "Ando TV" to "安多卫视",
        "Angel TV Chinese" to "天使中文台",
        "Anhui TV" to "安徽卫视",
        "Anshun Comprehensive News Channel" to "安顺新闻综合频道",
        "Baicheng TV" to "白城电视台",
        "Beijing Satellite TV" to "北京卫视",
        "Bingtuan Satellite TV" to "兵团卫视",
        "Bread TV" to "面包台",
        "BRTV Kaku Childrens Channel" to "卡酷少儿",
        "CCTV-1" to "CCTV-1 综合",
        "CCTV-2" to "CCTV-2 财经",
        "CCTV-3" to "CCTV-3 综艺",
        "CCTV-4K" to "CCTV-4K 超高清",
        "CCTV-5+" to "CCTV-5+ 体育赛事",
        "CCTV-6" to "CCTV-6 电影",
        "CCTV-7" to "CCTV-7 国防军事",
        "CCTV-8" to "CCTV-8 电视剧",
        "CCTV-8K" to "CCTV-8K 超高清",
        "CCTV-9" to "CCTV-9 纪录",
        "CCTV-10" to "CCTV-10 科教",
        "CCTV-11" to "CCTV-11 戏曲",
        "CCTV-12" to "CCTV-12 社会与法",
        "CCTV-13" to "CCTV-13 新闻",
        "CCTV-14" to "CCTV-14 少儿",
        "CCTV-15" to "CCTV-15 音乐",
        "CCTV-16" to "CCTV-16 奥林匹克",
        "CCTV-17" to "CCTV-17 农业农村",
        "CCTV-Billiards" to "央视台球",
        "CCTV-Culture of Quality" to "央视精品文化",
        "CCTV-Golf & Tennis" to "央视高尔夫网球",
        "CCTV-Health" to "央视健康",
        "CCTV-Nostalgia Theater" to "央视怀旧剧场",
        "CCTV+ 1" to "CCTV+ 1",
        "CCTV+ 2" to "CCTV+ 2",
        "CCTV-Storm Football" to "央视风云足球",
        "CCTV-Storm Music" to "央视风云音乐",
        "CCTV-Storm Theater" to "央视风云剧场",
        "CCTV-The First Theater" to "央视第一剧场",
        "CCTV-Weapon & Technology" to "央视兵器科技",
        "CCTV-Women's Fashion" to "央视女性时尚",
        "CCTV-World Geography" to "央视世界地理",
        "CETV-1" to "中国教育电视台一套",
        "CETV-2" to "中国教育电视台二套",
        "Chaozhou Public Channel" to "潮州公共频道",
        "CHC Action" to "CHC 动作电影",
        "CHC Home Theater" to "CHC 家庭影院",
        "Chifeng Comprehensive News Chanel" to "赤峰新闻综合频道",
        "China Weather Channel" to "中国天气频道",
        "Chuxiong News Channel" to "楚雄新闻频道",
        "Chuzhou News Channel" to "滁州新闻频道",
        "Chuzhou Public Channel" to "滁州公共频道",
        "Chuzhou Science & Education Channel" to "滁州科教频道",
        "CND Film Discovery Channel" to "CND 影视发现",
        "CND Film Middle School Channel" to "CND 影视中学",
        "Documentary Humanities Channel" to "纪实人文频道",
        "Dongguan Comprehensive News Channel" to "东莞新闻综合频道",
        "Dragon TV International" to "东方卫视国际",
        "Dunhuang TV" to "敦煌电视台",
        "Fengshang Shopping Channel" to "风尚购物",
        "Fujian Comprehensive Channel" to "福建综合频道",
        "Golden Eagle Cartoon" to "金鹰卡通",
        "Guangdong Satellite TV" to "广东卫视",
        "Guangxi Variety & Travel Channel" to "广西综艺旅游频道",
        "Guangzhou TV" to "广州电视台",
        "Guizhou TV" to "贵州卫视",
        "Harbin Comprehensive News Channel" to "哈尔滨新闻综合频道",
        "Harbin Movie Channel" to "哈尔滨影视频道",
        "Hebei TV" to "河北卫视",
        "Hebi News Channel" to "鹤壁新闻频道",
        "Heilongjiang TV" to "黑龙江卫视",
        "Hezheng TV" to "和政电视台",
        "High Channel TV" to "高清频道",
        "HomePlus" to "HomePlus",
        "Hunan Economy Channel" to "湖南经视",
        "Hunan News Channel" to "湖南新闻频道",
        "Hunan Politics & Law Channel" to "湖南政法频道",
        "Hunan TV" to "湖南卫视",
        "Hunan Womens Channel" to "湖南女性频道",
        "Jiangsu City Channel" to "江苏城市频道",
        "Jiangsu Educational Channel" to "江苏教育频道",
        "Jiangsu Movie Channel" to "江苏影视频道",
        "Jiangsu Public & News Channel" to "江苏公共新闻频道",
        "Jiangsu Satellite TV" to "江苏卫视",
        "Jiangsu Sports & Leisure Channel" to "江苏体育休闲频道",
        "Jiangsu Variety Channel" to "江苏综艺频道",
        "Jiangxi Children's Channel" to "江西少儿频道",
        "Jiangxi City Channel" to "江西城市频道",
        "Jiangxi Economy & Life Channel" to "江西经济生活频道",
        "Jiangxi Movie Channel" to "江西影视频道",
        "Jiangxi Public & Agriculture Channel" to "江西公共农业频道",
        "Jiangxi TV" to "江西卫视",
        "Jilin City Channel" to "吉林市电视台",
        "Jilin Lifestyle Channel" to "吉林生活频道",
        "Jilin Movie Channel" to "吉林影视频道",
        "Jilin Rural Channel" to "吉林乡村频道",
        "Jingmen Comprehensive News Channel" to "荆门新闻综合频道",
        "Jiuquan TV News Comprehensive Channel" to "酒泉新闻综合频道",
        "Kangba TV" to "康巴卫视",
        "Lanzhou Comprehensive News Channel" to "兰州新闻综合频道",
        "Lanzhou Culture & Tourism Channel" to "兰州文旅频道",
        "Liangshan TV" to "凉山电视台",
        "Liaoning TV" to "辽宁卫视",
        "Nanchang News & Generalist Channel" to "南昌新闻综合频道",
        "Nei Monggol TV 2 Mongolian Culture Channel" to "内蒙古蒙语文化频道",
        "Nei Monggol TV" to "内蒙古卫视",
        "Ningxia Satellite Channel" to "宁夏卫视",
        "Pingxiang TV News Channel" to "萍乡新闻频道",
        "Qianjiang Channel" to "钱江频道",
        "Qinghai TV" to "青海卫视",
        "QTV-1" to "青岛新闻综合频道",
        "QTV-2" to "青岛生活服务频道",
        "QTV-3" to "青岛影视频道",
        "QTV-4" to "青岛娱乐频道",
        "QTV-5" to "青岛都市频道",
        "QTV-6" to "青岛少儿频道",
        "Shandong Satellite TV" to "山东卫视",
        "Shandong TV Agricultural Science Channel" to "山东农科频道",
        "Shandong TV Children's Channel" to "山东少儿频道",
        "Shandong TV" to "山东电视台",
        "Shandong TV Life Channel" to "山东生活频道",
        "Shandong TV Qilu Channel" to "山东齐鲁频道",
        "Shandong TV Sports Channel" to "山东体育频道",
        "Shandong TV Variety Channel" to "山东综艺频道",
        "Shenzhen Satellite TV" to "深圳卫视",
        "Sichuan Satellite TV" to "四川卫视",
        "Sichuan TV Women and Children Channel" to "四川妇女儿童频道",
        "Siping TV" to "四平电视台",
        "Tianjin TV" to "天津卫视",
        "Tonghua TV" to "通化电视台",
        "TV BRICS Chinese" to "金砖国家电视台中文频道",
        "VoA TV China" to "美国之音中文电视",
        "Xi'an Business Information Channel" to "西安商务资讯频道",
        "Xi'an Silk Road Channel" to "西安丝路频道",
        "Xinjiang TV 1" to "新疆卫视",
        "Xinjiang TV 2" to "新疆电视台二套",
        "Xinjiang TV 3" to "新疆电视台三套",
        "Xinjiang TV 8" to "新疆电视台八套",
        "Xizang TV Chinese" to "西藏卫视",
        "Xizang TV Tibetan" to "西藏藏语卫视",
        "Xuzhou Economic Life Channel" to "徐州经济生活频道",
        "Yanbian Satellite TV" to "延边卫视",
        "Yichang Sanxia General Channel" to "宜昌三峡综合频道",
        "Yichang Sanxia Travel & Life Channel" to "宜昌三峡旅游生活频道",
        "You Man Cartoon Channel" to "优漫卡通",
        "Yunnan Satellite TV" to "云南卫视",
        "Zhejiang Children's Channel" to "浙江少儿频道",
        "Zhejiang Economic Channel" to "浙江经济频道",
        "Zhejiang Education Technology Channel" to "浙江教科频道",
        "Zhejiang International Channel" to "浙江国际频道",
        "Zhejiang Minsheng Leisure" to "浙江民生休闲频道",
        "Zhejiang Satellite TV" to "浙江卫视",
    )

    private val groups = mapOf(
        "General" to "综合",
        "News" to "新闻",
        "Religious" to "宗教",
        "Kids" to "少儿",
        "Education" to "科教",
        "Music" to "音乐",
        "Lifestyle" to "生活",
        "Business" to "财经",
        "Entertainment" to "娱乐",
        "Movies" to "影视",
        "Culture" to "文化",
        "Sports" to "体育",
        "Documentary" to "纪实",
        "Classic" to "经典",
        "Shop" to "购物",
        "Weather" to "天气",
        "Animation" to "动画",
        "Legislative" to "法治",
        "Science" to "科技",
        "Family" to "家庭",
        "Travel" to "旅游",
        "Outdoor" to "户外",
        "Undefined" to "其他",
    )

    /** The four broad sections OneTV uses instead of the playlist's technical groups. */
    enum class Category { CCTV, SATELLITE, LOCAL, OTHER }

    private val nationalNonCctv = listOf(
        "CETV", "中国教育电视台", "中国天气", "CHC", "CND", "金砖国家",
        "美国之音", "VoA", "ABN", "Angel TV", "Bread TV", "HomePlus",
    )

    private val localMarkers = listOf(
        "频道", "电视台", "新闻", "公共", "都市", "生活", "影视", "综艺",
        "少儿", "体育", "教育", "科教", "经济", "民生", "文旅", "交通",
        "法治", "政法", "购物", "纪实", "文化", "旅游", "农业", "乡村",
        "城市", "娱乐", "音乐", "天气", "卡通", "综合",
    )

    /** Classifies a channel by the name viewers know, not by the playlist's category tags. */
    fun category(name: String): Category {
        val shown = display(name)
        val raw = name.trim()
        return when {
            shown.startsWith("CCTV") || shown.startsWith("央视") ||
                raw.startsWith("CCTV", ignoreCase = true) || raw.startsWith("CGTN", ignoreCase = true) ||
                shown.contains("CGTN", ignoreCase = true) -> Category.CCTV
            shown.contains("卫视") || shown.contains("卫星") ||
                raw.contains("Satellite", ignoreCase = true) -> Category.SATELLITE
            nationalNonCctv.any { raw.contains(it, ignoreCase = true) || shown.contains(it, ignoreCase = true) } ->
                Category.OTHER
            localMarkers.any { shown.contains(it) } -> Category.LOCAL
            else -> Category.OTHER
        }
    }

    private val quality = Regex("""\s*\(\s*\d{3,4}[pi]\s*\)""", RegexOption.IGNORE_CASE)
    private val tag = Regex("""\s*\[\s*(?:Not\s+24/7|Geo-blocked)\s*]""", RegexOption.IGNORE_CASE)
    private val definition = Regex("""\s+(?:HD|SD|FHD|UHD)$""", RegexOption.IGNORE_CASE)

    /** A Chinese name for a known channel, otherwise the playlist's own name. */
    fun display(name: String): String {
        val base = name.trim()
            .replace(tag, "")
            .replace(quality, "")
            .replace(definition, "")
            .trim()
        return names[base] ?: name.trim()
    }

    /** Localises a group while preserving several group values such as "Education;Outdoor". */
    fun group(value: String): String = value.split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("、") { groups[it] ?: it }
        .ifEmpty { "其他" }
}
