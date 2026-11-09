package com.hhst.youtubelite.player.surface

import org.junit.Assert.*
import org.junit.Test

class FullscreenSwipeMotionTest {
    @Test fun entryFitsInsideTheEmbeddedPictureAndAcceptsAShortIntentionalFlick() {
        for (density in listOf(1f, 2.75f, 4f)) {
            val height = 230f * density
            assertTrue(FullscreenSwipeMotion.entryThreshold(height, density) <= 56f * density)
            assertTrue(FullscreenSwipeMotion.commitsEntry(56f * density, 0f, height, density))
            assertTrue(FullscreenSwipeMotion.commitsEntry(30f * density, 600f * density, height, density))
            assertFalse(FullscreenSwipeMotion.commitsEntry(18f * density, 10_000f * density, height, density))
            assertFalse(FullscreenSwipeMotion.commitsEntry(28f * density, 0f, height, density))
            assertFalse(FullscreenSwipeMotion.commitsEntry(70f * density, -800f * density, height, density))
        }
    }

    @Test fun draggingIsDampedRetraceableAndDoesNotHitAHardEdge() {
        assertEquals(0f, FullscreenSwipeMotion.progress(-100f, 240f, 1f), 0f)
        assertEquals(0f, FullscreenSwipeMotion.progress(12f, 240f, 1f), 0f)
        var previous = 0f
        for (distance in 0..2000) {
            val progress = FullscreenSwipeMotion.progress(distance.toFloat(), 240f, 1f)
            assertTrue(progress >= previous)
            assertTrue(progress < 1f)
            assertTrue(progress - previous < 1f / 240f)
            assertEquals(progress, FullscreenSwipeMotion.progress(distance * 3f, 720f, 3f), .00001f)
            previous = progress
        }
        assertTrue(FullscreenSwipeMotion.progress(700f, 240f, 1f) > FullscreenSwipeMotion.progress(600f, 240f, 1f))
    }

    @Test fun releaseMatchesFingerVelocityAndEndsAtRestWithoutOvershoot() {
        val speed = FullscreenSwipeMotion.progressVelocity(120f, 600f, 240f, 1f)
        assertTrue(speed > 0f)
        val slope = FullscreenSwipeMotion.settleSlope(speed, .6f, 240)
        val dt = .0001f
        val sampledSpeed = FullscreenSwipeMotion.settleProgress(dt, slope) / dt * .6f / .24f
        assertEquals(speed, sampledSpeed, .01f)
        for (initialSlope in listOf(0f, .5f, 1f, 3f, -1f)) {
            assertEquals(0f, FullscreenSwipeMotion.settleProgress(0f, initialSlope), 0f)
            assertEquals(1f, FullscreenSwipeMotion.settleProgress(1f, initialSlope), .00001f)
            assertEquals(0f, (1f - FullscreenSwipeMotion.settleProgress(1f - dt, initialSlope)) / dt, .02f)
            for (step in 0..1000) {
                val progress = FullscreenSwipeMotion.settleProgress(step / 1000f, initialSlope)
                assertTrue(progress <= 1.00001f)
                assertTrue(progress >= -.1f)
            }
        }
    }

    @Test fun pictureFeedbackIsSizedToThePictureAndRetracesWithoutChangingItsDimensions() {
        for (density in listOf(1f, 2.75f, 4f)) {
            val height = 180f * density
            assertEquals(0f, FullscreenSwipeMotion.pictureZoom(-10f * density, height, density), 0f)
            val zoom = FullscreenSwipeMotion.pictureZoom(60f * density, height, density)
            assertTrue("A short entry swipe visibly magnifies the picture", zoom > .1f)
            assertEquals(FullscreenSwipeMotion.pictureZoom(60f, 180f, 1f), zoom, .00001f)
            val offset = FullscreenSwipeMotion.pictureTranslation(120f * density, density)
            assertTrue("An intentional downward swipe meets visible resistance", offset > 30f * density && offset < 60f * density)
            assertEquals(0f, FullscreenSwipeMotion.pictureTranslation(-10f * density, density), 0f)
            assertTrue(FullscreenSwipeMotion.pictureTranslation(60f * density, density) < offset)
            assertTrue(FullscreenSwipeMotion.pictureTranslationVelocity(120f * density, 600f * density, density) > 0f)
            assertTrue(FullscreenSwipeMotion.pictureTranslationVelocity(120f * density, -600f * density, density) < 0f)
            assertTrue(FullscreenSwipeMotion.pictureZoomVelocity(60f * density, -600f * density, height, density) < 0f)
        }
        assertEquals(320f, FullscreenSwipeMotion.pictureWidth(320f, 600f, 16f / 9f, ResizeMode.Fit), .001f)
        assertEquals(180f, FullscreenSwipeMotion.pictureWidth(320f, 180f, 1f, ResizeMode.Fit), .001f)
        assertEquals(600f, FullscreenSwipeMotion.pictureWidth(320f, 600f, 1f, ResizeMode.Zoom), .001f)
        assertEquals(320f, FullscreenSwipeMotion.pictureWidth(320f, 600f, 1f, ResizeMode.Fill), .001f)
    }

    @Test fun longerDragsMeetIncreasingResistanceAndReleaseWithTheirResistedVelocity() {
        for (density in listOf(1f, 2.75f, 4f)) {
            val distances = listOf(60f, 120f, 180f, 240f)
            val offsets = distances.map { FullscreenSwipeMotion.pictureTranslation(it * density, density) }
            val gains = offsets.zipWithNext { a, b -> b - a }
            assertTrue("The same finger travel must move the picture progressively less", gains[0] > gains[1] && gains[1] > gains[2])
            assertTrue("A long drag must not carry the picture offscreen", FullscreenSwipeMotion.pictureTranslation(1000f * density, density) < 120f * density)
            val shortSpeed = FullscreenSwipeMotion.pictureTranslationVelocity(60f * density, 600f * density, density)
            val longSpeed = FullscreenSwipeMotion.pictureTranslationVelocity(240f * density, 600f * density, density)
            assertTrue("Release speed inherits the same increasing resistance", longSpeed > 0f && longSpeed < shortSpeed * .5f)
            for (travel in listOf(120f, 240f, 480f)) {
                val progress = distances.map { FullscreenSwipeMotion.progress(it * density, travel * density, density) }
                val increments = progress.zipWithNext { a, b -> b - a }
                assertTrue("Container drags also meet increasing resistance", increments[0] > increments[1] && increments[1] > increments[2])
            }
            assertTrue("Resistance does not raise the entry threshold", FullscreenSwipeMotion.commitsEntry(60f * density, 0f, 180f * density, density))
        }
    }

    @Test fun cancellationRetainsReverseMotionAndShortReturnsSettleSooner() {
        val reverse = FullscreenSwipeMotion.settleSlope(100f, -50f, 180)
        assertTrue(reverse < 0f)
        assertTrue(FullscreenSwipeMotion.settleProgress(.02f, reverse) < 0f)
        assertTrue(FullscreenSwipeMotion.settleDuration(.05f) < FullscreenSwipeMotion.settleDuration(.8f))
        assertEquals(120, FullscreenSwipeMotion.settleDuration(0f))
        assertEquals(240, FullscreenSwipeMotion.settleDuration(10f))
        assertEquals(0f, FullscreenSwipeMotion.progressVelocity(0f, 10_000f, 240f, 1f), 0f)
    }
}
