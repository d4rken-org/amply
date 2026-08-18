package eu.darken.amply.stats.core

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChargeTimeEstimatorTest {

    /** Every 1% step from [from] until [to] at [millis] each, as one session's observations. */
    private fun steps(
        sessionId: Long,
        from: Int,
        to: Int,
        millis: Long,
        type: ChargingType = ChargingType.AC,
    ) = (from until to).map { percent ->
        BandObservation(sessionId = sessionId, chargingType = type, percentFrom = percent, millis = millis)
    }

    private fun project(
        model: ChargeTimeModel,
        percent: Int,
        type: ChargingType = ChargingType.AC,
    ) = ChargeTimeEstimator.project(model, percent, type)

    @Test
    fun `a band is usable only once two distinct sessions have crossed it`() {
        // One session crossing 40-60 produces twenty observations. Counting observations would call
        // that corroborated; counting sessions does not.
        val single = ChargeTimeEstimator.buildModel(steps(1L, 40, 60, 60_000))
        single.pooled.bands.isEmpty() shouldBe true
        single.hasData shouldBe false

        val both = ChargeTimeEstimator.buildModel(steps(1L, 40, 60, 60_000) + steps(2L, 40, 60, 90_000))
        both.pooled.bands.keys shouldBe setOf(40, 50)
    }

    @Test
    fun `a band's rate is the median across sessions, not across observations`() {
        // Three sessions crossing 30-40 at 1, 2 and 6 minutes per percent. The median session rate
        // is 2 minutes; a mean would be pulled to 3 by the outlier.
        val model = ChargeTimeEstimator.buildModel(
            steps(1L, 30, 40, 60_000) + steps(2L, 30, 40, 120_000) + steps(3L, 30, 40, 360_000),
        )
        model.pooled.bands[30] shouldBe 120_000L
    }

    @Test
    fun `a target is null as soon as one band between here and it is unusable`() {
        // Two sessions covered 40-80, but only one ever went above 80.
        val model = ChargeTimeEstimator.buildModel(
            steps(1L, 40, 80, 60_000) + steps(2L, 40, 80, 60_000) + steps(1L, 80, 100, 180_000),
        )
        val projection = project(model, 40).shouldNotBeNull()
        // 40 → 80 is 40 steps of a minute each.
        projection.estimate.toEightyMillis shouldBe 40 * 60_000L
        // The 80-100 stretch has one session behind it, so a full-charge figure would be a guess.
        projection.estimate.toFullMillis.shouldBeNull()
        projection.estimate.split.eightyToHundredMillis.shouldBeNull()
    }

    @Test
    fun `at eighty percent and above there is nothing left to count down to`() {
        val model = ChargeTimeEstimator.buildModel(
            steps(1L, 40, 100, 60_000) + steps(2L, 40, 100, 60_000),
        )
        project(model, 80).shouldNotBeNull().estimate.toEightyMillis.shouldBeNull()
        project(model, 92).shouldNotBeNull().estimate.toEightyMillis.shouldBeNull()
        // ...but the full target is still real, and shorter from higher up.
        project(model, 92).shouldNotBeNull().estimate.toFullMillis shouldBe 8 * 60_000L
        project(model, 100).shouldNotBeNull().estimate.toFullMillis.shouldBeNull()
    }

    @Test
    fun `the band split segments are independently nullable`() {
        // A user who never drains below 50%: the lower segment is unknown, the rest is not.
        val model = ChargeTimeEstimator.buildModel(
            steps(1L, 50, 100, 60_000) + steps(2L, 50, 100, 60_000),
        )
        val split = project(model, 60).shouldNotBeNull().estimate.split
        split.toFiftyMillis.shouldBeNull()
        split.fiftyToEightyMillis shouldBe 30 * 60_000L
        split.eightyToHundredMillis shouldBe 20 * 60_000L
        split.hasAny shouldBe true
    }

    @Test
    fun `projections are stratified by charger type`() {
        val model = ChargeTimeEstimator.buildModel(
            steps(1L, 40, 100, 60_000, ChargingType.AC) +
                steps(2L, 40, 100, 60_000, ChargingType.AC) +
                steps(3L, 40, 100, 240_000, ChargingType.WIRELESS) +
                steps(4L, 40, 100, 240_000, ChargingType.WIRELESS),
        )
        val wired = project(model, 40, ChargingType.AC).shouldNotBeNull()
        val wireless = project(model, 40, ChargingType.WIRELESS).shouldNotBeNull()

        wired.basis shouldBe ChargeTimeBasis.SAME_TYPE
        wireless.basis shouldBe ChargeTimeBasis.SAME_TYPE
        wired.estimate.toFullMillis shouldBe 60 * 60_000L
        wireless.estimate.toFullMillis shouldBe 60 * 240_000L
    }

    @Test
    fun `an unseen charger type falls back to the pooled history and says so`() {
        val model = ChargeTimeEstimator.buildModel(
            steps(1L, 40, 100, 60_000, ChargingType.AC) + steps(2L, 40, 100, 60_000, ChargingType.AC),
        )
        val projection = project(model, 40, ChargingType.WIRELESS).shouldNotBeNull()
        projection.basis shouldBe ChargeTimeBasis.POOLED
        projection.estimate.toFullMillis shouldBe 60 * 60_000L
    }

    @Test
    fun `average speed is the median of the contributing sessions' own averages`() {
        val model = ChargeTimeEstimator.buildModel(
            observations = steps(1L, 40, 60, 60_000) + steps(2L, 40, 60, 60_000) + steps(3L, 40, 60, 60_000),
            sessionPowerMilliwatts = mapOf(1L to 8_000, 2L to 12_000, 3L to 25_000),
        )
        project(model, 40).shouldNotBeNull().estimate.avgSpeedMilliwatts shouldBe 12_000
    }

    @Test
    fun `basedOnSessions counts distinct contributing sessions`() {
        val model = ChargeTimeEstimator.buildModel(
            steps(1L, 40, 60, 60_000) + steps(2L, 40, 60, 60_000) + steps(3L, 40, 60, 60_000),
        )
        project(model, 40).shouldNotBeNull().estimate.basedOnSessions shouldBe 3
        // The count is of contributors, not of everything the extractor ever saw.
        model.observedSessions shouldBe 3
    }

    @Test
    fun `a band only one session reached contributes nobody to the count`() {
        val model = ChargeTimeEstimator.buildModel(
            steps(1L, 40, 60, 60_000) + steps(2L, 40, 60, 60_000) + steps(9L, 80, 100, 60_000),
        )
        project(model, 40).shouldNotBeNull().estimate.basedOnSessions shouldBe 2
    }

    @Test
    fun `the projection changes with the level while the model stays the same`() {
        val model = ChargeTimeEstimator.buildModel(
            steps(1L, 0, 100, 60_000) + steps(2L, 0, 100, 60_000),
        )
        project(model, 20).shouldNotBeNull().estimate.toFullMillis shouldBe 80 * 60_000L
        project(model, 60).shouldNotBeNull().estimate.toFullMillis shouldBe 40 * 60_000L
        project(model, 99).shouldNotBeNull().estimate.toFullMillis shouldBe 60_000L
    }

    @Test
    fun `an empty history projects nothing at all`() {
        val model = ChargeTimeEstimator.buildModel(emptyList())
        model.hasData shouldBe false
        project(model, 40).shouldBeNull()
    }
}
