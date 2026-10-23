package com.utopia.racechronobridge.ssm2.catalog

/**
 * RomRaider ロガー定義の換算式 (例: `(x-128)/2`, `14.7/(x*.0004882812)`) を評価する。
 *
 * 定義の SSM パラメータで使われている文法だけを受け付ける: 数値・`x`・`+ - * /`・括弧・単項マイナス。
 * それ以外の文字があれば、表の読み込み時に例外にする（黙って誤った値を出さないため）。
 */
class SsmExpression private constructor(
    val source: String,
    private val root: Node,
) {
    fun evaluate(x: Double): Double = root.eval(x)

    override fun toString(): String = source

    private sealed interface Node {
        fun eval(x: Double): Double
    }

    private data class Constant(val value: Double) : Node {
        override fun eval(x: Double) = value
    }

    private data object Variable : Node {
        override fun eval(x: Double) = x
    }

    private data class Negate(val operand: Node) : Node {
        override fun eval(x: Double) = -operand.eval(x)
    }

    private data class Binary(val op: Char, val left: Node, val right: Node) : Node {
        override fun eval(x: Double): Double {
            val l = left.eval(x)
            val r = right.eval(x)
            return when (op) {
                '+' -> l + r
                '-' -> l - r
                '*' -> l * r
                '/' -> l / r
                else -> error("unknown operator $op")
            }
        }
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parse(): Node {
            val node = sum()
            skipSpaces()
            if (pos != text.length) fail("unexpected '${text[pos]}'")
            return node
        }

        private fun sum(): Node {
            var node = product()
            while (true) {
                skipSpaces()
                val op = peek()
                if (op != '+' && op != '-') return node
                pos++
                node = Binary(op, node, product())
            }
        }

        private fun product(): Node {
            var node = unary()
            while (true) {
                skipSpaces()
                val op = peek()
                if (op != '*' && op != '/') return node
                pos++
                node = Binary(op, node, unary())
            }
        }

        private fun unary(): Node {
            skipSpaces()
            return when (peek()) {
                '-' -> {
                    pos++
                    Negate(unary())
                }
                '+' -> {
                    pos++
                    unary()
                }
                else -> primary()
            }
        }

        private fun primary(): Node {
            skipSpaces()
            val c = peek() ?: fail("unexpected end")
            return when {
                c == '(' -> {
                    pos++
                    val inner = sum()
                    skipSpaces()
                    if (peek() != ')') fail("missing ')'")
                    pos++
                    inner
                }
                c == 'x' || c == 'X' -> {
                    pos++
                    Variable
                }
                c.isDigit() || c == '.' -> number()
                else -> fail("unexpected '$c'")
            }
        }

        private fun number(): Node {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            val token = text.substring(start, pos)
            return Constant(token.toDoubleOrNull() ?: fail("bad number '$token'"))
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun skipSpaces() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("Invalid SSM expression '$text' at $pos: $message")
    }

    companion object {
        fun parse(source: String): SsmExpression = SsmExpression(source, Parser(source).parse())
    }
}
