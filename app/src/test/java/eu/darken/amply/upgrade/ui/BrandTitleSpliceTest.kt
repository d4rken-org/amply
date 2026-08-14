package eu.darken.amply.upgrade.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The brand is spliced into the already-formatted translation, so the styled postfix has to land on
 * the right offsets no matter where the pattern put its placeholders.
 */
class BrandTitleSpliceTest {

    private val brandColor = Color.Red

    // "Amply Pro" with the postfix (6..9) colored, like upgradeScreenTitle(upgraded = true).
    private val brand: AnnotatedString = buildAnnotatedString {
        append("Amply ")
        pushStyle(SpanStyle(color = brandColor))
        append("Pro")
        pop()
    }

    private val name = AnnotatedString("Amply")
    private val qualifier = buildAnnotatedString {
        pushStyle(SpanStyle(color = brandColor))
        append("Pro")
        pop()
    }

    // region spliceBrandTitle — one slot, inside a sentence

    @Test
    fun `marker in the middle shifts the styled postfix by the prefix`() {
        val result = spliceBrandTitle("Get $BRAND_TITLE_MARKER", brand)

        result.text shouldBe "Get Amply Pro"
        result.spanStyles.size shouldBe 1
        result.spanStyles.single().item.color shouldBe brandColor
        result.spanStyles.single().start shouldBe 10
        result.spanStyles.single().end shouldBe 13
        result.text.substring(10, 13) shouldBe "Pro"
    }

    @Test
    fun `marker at the start keeps the postfix offsets inside the brand`() {
        val result = spliceBrandTitle("$BRAND_TITLE_MARKER holen", brand)

        result.text shouldBe "Amply Pro holen"
        result.spanStyles.size shouldBe 1
        result.spanStyles.single().start shouldBe 6
        result.spanStyles.single().end shouldBe 9
        result.text.substring(6, 9) shouldBe "Pro"
    }

    @Test
    fun `a duplicated marker renders the brand twice`() {
        val result = spliceBrandTitle("$BRAND_TITLE_MARKER und $BRAND_TITLE_MARKER", brand)

        result.text shouldBe "Amply Pro und Amply Pro"
        result.spanStyles.size shouldBe 2
        result.spanStyles[0].start shouldBe 6
        result.spanStyles[0].end shouldBe 9
        result.spanStyles[1].start shouldBe 20
        result.spanStyles[1].end shouldBe 23
        result.text.substring(20, 23) shouldBe "Pro"
    }

    @Test
    fun `a translation that lost the placeholder still shows the brand`() {
        val result = spliceBrandTitle("Get Pro", brand)

        result.text shouldBe "Get Pro Amply Pro"
        result.spanStyles.size shouldBe 1
        result.spanStyles.single().item.color shouldBe brandColor
        result.spanStyles.single().start shouldBe 14
        result.spanStyles.single().end shouldBe 17
    }

    // endregion

    // region spliceTitleTemplate — two slots, a whole title

    @Test
    fun `both slots are filled in template order`() {
        val result = spliceTitleTemplate(
            formatted = "$BRAND_TITLE_MARKER $BRAND_QUALIFIER_MARKER",
            name = name,
            qualifier = qualifier,
        )

        result.text shouldBe "Amply Pro"
        result.spanStyles.single().start shouldBe 6
        result.spanStyles.single().end shouldBe 9
    }

    @Test
    fun `a reordered template keeps each slot with its own value`() {
        // A translation that puts the tier word first must not swap what the styling applies to.
        val result = spliceTitleTemplate(
            formatted = "$BRAND_QUALIFIER_MARKER de $BRAND_TITLE_MARKER",
            name = name,
            qualifier = qualifier,
        )

        result.text shouldBe "Pro de Amply"
        result.spanStyles.single().start shouldBe 0
        result.spanStyles.single().end shouldBe 3
    }

    @Test
    fun `a template missing a slot is discarded whole`() {
        // Patching it up piecewise would emit a title no translator wrote.
        val result = spliceTitleTemplate(
            formatted = "just $BRAND_TITLE_MARKER",
            name = name,
            qualifier = qualifier,
        )

        result.text shouldBe "Amply Pro"
    }

    @Test
    fun `a template with a doubled slot is discarded whole`() {
        val result = spliceTitleTemplate(
            formatted = "$BRAND_TITLE_MARKER $BRAND_TITLE_MARKER $BRAND_QUALIFIER_MARKER",
            name = name,
            qualifier = qualifier,
        )

        result.text shouldBe "Amply Pro"
    }

    // endregion
}
