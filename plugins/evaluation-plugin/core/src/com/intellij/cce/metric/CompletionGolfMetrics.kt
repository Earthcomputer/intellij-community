package com.intellij.cce.metric

import com.intellij.cce.core.Session
import com.intellij.cce.metric.util.Sample

internal fun createCompletionGolfMetrics(): List<Metric> =
  listOf(
    MovesCount(),
    TypingsCount(),
    NavigationsCount(),
    CompletionInvocationsCount(),
    MovesCountNormalised(),
    PerfectLine(),
    RecallAt(1),
    RecallAt(5)
  )

internal abstract class CompletionGolfMetric<T : Number> : Metric {
  protected var sample = Sample()

  private fun T.alsoAddToSample(): T = also { sample.add(it.toDouble()) }

  override fun evaluate(sessions: List<Session>, comparator: SuggestionsComparator): T = compute(sessions, comparator).alsoAddToSample()

  abstract fun compute(sessions: List<Session>, comparator: SuggestionsComparator): T
}

internal class MovesCount : CompletionGolfMetric<Int>() {
  override val name = NAME
  override val valueType = MetricValueType.INT
  override val value: Double
    get() = sample.sum()

  private val metrics = listOf(
    TypingsCount(),
    NavigationsCount(),
    CompletionInvocationsCount()
  )

  override fun compute(sessions: List<Session>, comparator: SuggestionsComparator): Int {
    // Add x2 amount of lookups, assuming that before each action we call code completion
    // We summarize 3 types of actions:
    // call code completion (1 point)
    // choice suggestion from completion or symbol (if there is no offer in completion) (1 point)
    // navigation to the suggestion (if it fits) (N points, based on suggestion index, assuming first index is 0)
    return metrics.sumOf { it.compute(sessions, comparator) }
  }

  companion object {
    const val NAME = "Total Moves"
  }
}

internal class TypingsCount : CompletionGolfMetric<Int>() {
  override val name = NAME
  override val valueType = MetricValueType.INT
  override val showByDefault = false
  override val value: Double
    get() = sample.sum()

  override fun compute(sessions: List<Session>, comparator: SuggestionsComparator): Int =
    sessions.sumOf { it.lookups.count() + it.totalSkippableChars() - it.completedSkippableChars() }

  companion object {
    const val NAME = "Typings"
  }
}

internal class NavigationsCount : CompletionGolfMetric<Int>() {
  override val name = NAME
  override val valueType = MetricValueType.INT
  override val showByDefault = false
  override val value: Double
    get() = sample.sum()

  override fun compute(sessions: List<Session>, comparator: SuggestionsComparator) = sessions.sumOf { computeMoves(it) }

  private fun computeMoves(session: Session) = session.lookups.sumOf { if (it.selectedPosition >= 0) it.selectedPosition else 0 }

  companion object {
    const val NAME = "Navigations"
  }
}


internal class CompletionInvocationsCount : CompletionGolfMetric<Int>() {
  override val name = NAME
  override val valueType = MetricValueType.INT
  override val showByDefault = false
  override val value: Double
    get() = sample.sum()

  override fun compute(sessions: List<Session>, comparator: SuggestionsComparator): Int =
    sessions.sumOf { it.lookups.count { lookup -> lookup.isNew } }

  companion object {
    const val NAME = "Completion Invocations"
  }
}

internal class MovesCountNormalised : Metric {
  private var movesCountTotal: Int = 0
  private var minPossibleMovesTotal: Int = 0
  private var maxPossibleMovesTotal: Int = 0

  override val name = NAME
  override val valueType = MetricValueType.DOUBLE
  override val value: Double
    get() = (movesCountTotal - minPossibleMovesTotal).toDouble() / (maxPossibleMovesTotal - minPossibleMovesTotal)

  override fun evaluate(sessions: List<Session>, comparator: SuggestionsComparator): Double {
    val movesCount = MovesCount().compute(sessions, comparator)
    val minPossibleMoves = sessions.count() * 2
    val maxPossibleMoves = sessions.sumOf { it.expectedText.length } * 2 - sessions.sumOf { it.totalSkippableChars() }
    movesCountTotal += movesCount
    minPossibleMovesTotal += minPossibleMoves
    maxPossibleMovesTotal += maxPossibleMoves

    // Since code completion's call and the choice of option (symbol) contains in each lookup,
    // It is enough to calculate the difference for the number of lookups and extra moves (for completion case)
    // To reach 0%, you also need to subtract the minimum number of lookups (eq. number of sessions plus minimum amount of completion calls)
    // 0% - best scenario, every line was completed from start to end with first suggestion in list
    // >100% is possible, when navigation in completion takes too many moves
    return ((movesCount - minPossibleMoves).toDouble() / (maxPossibleMoves - minPossibleMoves))
  }

  companion object {
    const val NAME = "Moves Count Normalised"
  }
}

internal class PerfectLine : CompletionGolfMetric<Int>() {
  override val name = NAME
  override val valueType = MetricValueType.INT
  override val value: Double
    get() = sample.sum()

  override fun compute(sessions: List<Session>, comparator: SuggestionsComparator): Int {
    return sessions.count { it.success }
  }

  companion object {
    const val NAME = "Perfect Line"
  }
}

internal class RecallAt(private val n: Int) : Metric {
  private val sample = Sample()
  override val name = NAME_PREFIX + n
  override val valueType = MetricValueType.DOUBLE
  override val value: Double
    get() = sample.mean()

  override fun evaluate(sessions: List<Session>, comparator: SuggestionsComparator): Double {
    val fileSample = Sample()

    for (lookup in sessions.flatMap { it.lookups }) {
      if (lookup.selectedPosition in 0 until n) {
        fileSample.add(1.0)
        sample.add(1.0)
      }
      else {
        fileSample.add(0.0)
        sample.add(0.0)
      }
    }
    return fileSample.mean()
  }

  companion object {
    const val NAME_PREFIX = "RecallAt"
  }
}

private fun Session.totalSkippableChars() = expectedText.count { it.isWhitespace() }

private fun Session.completedSkippableChars() = lookups.sumOf {
  if (it.selectedPosition < 0) 0
  else it.suggestions[it.selectedPosition].text.count { it.isWhitespace() }
}
