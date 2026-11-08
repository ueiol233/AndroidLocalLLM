package com.ueiol.scanaianswer.data.session

sealed class Message {
    abstract val content: String
    abstract val timestamp: Long

    data class User(
        override val content: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : Message()

    data class Assistant(
        override val content: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val isThinking: Boolean = false,
        /** 解析出的思考过程内容（不含 think 标签） */
        val thinkingContent: String = "",
        /** 解析出的正式回答内容 */
        val answerContent: String = content,
        /** 思考区域是否展开 */
        val isThinkingExpanded: Boolean = false,
        /** 思考开始时间戳（毫秒），用于计算思考时长 */
        val thinkingStartTime: Long = 0
    ) : Message() {
        companion object {
            /** 思考开启标记（模型可能输出不同形式）。按出现顺序取最早匹配。 */
            private val THINK_OPEN = listOf("<think>", " thinking", "<thinking>", " 思考：", " thinking", " 思考", " 思考", " think")

            /**
             * 思考结束标记。按具体性优先排列，避免误伤中文思考正文里的“回答”字样。
             * 混元等模型可能以 </answer> 作为“思考→正式回答”的切换标记（仅闭合标签，
             * 前面没有配对的 <answer> 开标签），因此必须纳入以免把真正的回答并进思考区。
             */
            private val THINK_CLOSE = listOf("</thinking>", "</answer>", " response", " 回答：", "<answer>", " answer")

            /** 提取 <answer>...</answer> 内容的预编译正则（流式每次解析复用一个实例）。 */
            private val ANSWER_RE = Regex("""<answer>(.*?)</answer>""", RegexOption.DOT_MATCHES_ALL)

            /**
             * 从原始内容解析 think/answer 标签。
             * 支持格式：
             *    thinking...思考内容... response正式回答
             *    thinking...思考内容... response<answer>正式回答</answer>（混元/部分模型）
             *   <thinking>...思考内容...</thinking>正式回答
             */
            fun parse(content: String, isThinking: Boolean = false): Assistant {
                if (isThinking) {
                    return Assistant(content = content, isThinking = true)
                }
                return parse(content, System.currentTimeMillis())
            }

            fun parse(content: String, timestamp: Long): Assistant {
                // 定位思考开启标记
                val open = locate(THINK_OPEN, content, 0)
                if (open == null) {
                    // 无思考块：整体当作回答（剥离可能的 <answer> 标签包裹）
                    return Assistant(
                        content = content,
                        timestamp = timestamp,
                        isThinking = false,
                        thinkingContent = "",
                        answerContent = extractAnswerContent(content)
                    )
                }

                val bodyStart = open.first + open.second
                // 思考结束标记（从开启标记之后查找）
                val close = locate(THINK_CLOSE, content, bodyStart)
                val thinkingRaw =
                    content.substring(bodyStart, close?.first ?: content.length)
                val restRaw =
                    if (close != null) content.substring(close.first + close.second) else ""

                return Assistant(
                    content = content,
                    timestamp = timestamp,
                    isThinking = false,
                    thinkingContent = thinkingRaw.trim(),
                    answerContent = extractAnswerContent(restRaw)
                )
            }

            /**
             * 流式输出时的实时解析。不等结束标签：一旦检测到思考开启标记，
             * 就实时把已输出的思考内容放进 [thinkingContent]（可展开查看）。
             */
            fun fromStream(content: String, prev: Assistant): Assistant {
                // 思考已结束（出现结束标记）→ 正常解析
                // 注意：仅在 prev 已经在思考状态时，才根据 THINK_CLOSE 结束思考；
                // 否则 THINK_CLOSE 中的 " response"/" answer" 等容易在正文里误触发。
                if (prev.isThinking && THINK_CLOSE.any { content.contains(it) }) {
                    // 关键：思考结束进入回答时保留展开状态。
                    // parse() 返回的对象不带 isThinkingExpanded（默认 false），若直接
                    // 用它替换，用户已展开的思考区会被强制收起，item 高度突变引发气泡错位。
                    return parse(content, prev.timestamp)
                        .copy(isThinkingExpanded = prev.isThinkingExpanded)
                }

                // 思考进行中：检测开启标记，实时提取已流出的思考内容
                val openEnd = indexOfOpenMarkerEnd(content)
                val inThought = prev.isThinking || openEnd >= 0
                if (inThought) {
                    val thinking = if (openEnd >= 0) {
                        content.substring(openEnd)
                    } else {
                        prev.thinkingContent
                    }
                    // 首次进入思考状态时记录开始时间
                    val startTime = if (prev.thinkingStartTime > 0) prev.thinkingStartTime else System.currentTimeMillis()
                    return Assistant(
                        content = content,
                        timestamp = prev.timestamp,
                        isThinking = true,
                        thinkingContent = thinking.trim(),
                        answerContent = "",
                        isThinkingExpanded = prev.isThinkingExpanded,
                        thinkingStartTime = startTime
                    )
                }

                // 没有思考标签：普通流式回答（剥离可能的 <answer> 标签包裹）
                return Assistant(
                    content = content,
                    timestamp = prev.timestamp,
                    isThinking = false,
                    thinkingContent = "",
                    answerContent = extractAnswerContent(content),
                    isThinkingExpanded = prev.isThinkingExpanded
                )
            }

            /** 从 [from] 起在 [text] 中查找最早命中的标记，返回(起点, 长度)；无则 null。 */
            private fun locate(markers: List<String>, text: String, from: Int): Pair<Int, Int>? {
                var best: Pair<Int, Int>? = null
                for (m in markers) {
                    val i = text.indexOf(m, from)
                    if (i >= 0 && (best == null || i < best.first)) {
                        best = i to m.length
                    }
                }
                return best
            }

            /** 返回思考开启标记之后的位置；未开启返回 -1。 */
            private fun indexOfOpenMarkerEnd(content: String): Int {
                val open = locate(THINK_OPEN, content, 0)
                return open?.let { it.first + it.second } ?: -1
            }

            /**
             * 从模型原始回答段中提取真正的正文：
             * 优先取 <answer>...</answer> 之间的内容（混元/部分模型把答案包在 answer 标签里）。
             * 模型关闭思考/不支持思考时没有 thinking 标记，可能直接以 <answer> 开场甚至会
             * 在流式过程中尚未输出 </answer>；因此在未取到成对标签时，只要检测到 <answer>
             * 就从其后开始提取正文，避免把 answer 之前的内容及标签残留带入。
             */
            private fun extractAnswerContent(raw: String): String {
                // 1. 优先提取成对的 <answer>...</answer> 包裹内容
                val m = ANSWER_RE.find(raw)
                if (m != null) return m.groupValues[1].trim()

                // 2. 未见闭合时，只要检测到 <answer> 就从其后开始正文
                val openIdx = raw.indexOf("<answer>")
                if (openIdx >= 0) {
                    var t = raw.substring(openIdx + "<answer>".length)
                    // 流式尚未闭合时 </answer> 可能还没有；若已出现则从中截断到结尾标签之后
                    t = t.substringBefore("</answer>")
                    return cleanMarkers(t)
                }

                // 3. 完全没有 answer 标签：清除其它可能的标签残留后当作普通正文
                var t = cleanMarkers(raw)
                // 剥掉开头的思考结束切换标记（response / answer）
                t = t.removePrefix("response").removePrefix("answer").trim()
                return t
            }

            /**
             * 清除正文中可能残留的 xml 式标签（answer/thinking/reasoning 的开闭标签）。
             */
            private fun cleanMarkers(raw: String): String {
                return raw
                    .replace("<answer>", "")
                    .replace("</answer>", "")
                    .replace("<thinking>", "")
                    .replace("</thinking>", "")
                    .replace("<reasoning>", "")
                    .replace("</reasoning>", "")
                    .trim()
            }
        }
    }
}