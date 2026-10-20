package com.gow3.android

import com.gow3.android.data.AndroidReleaseVersion
import com.gow3.android.data.GitHubRelease
import com.gow3.android.data.NotComparableReason
import com.gow3.android.data.UpdateCheckResult
import com.gow3.android.data.UpdateChecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val release = GitHubRelease(
        tag = "v2.1.6-android3",
        pageUrl = "https://github.com/zFitness/gow3_android/releases/tag/v2.1.6-android3",
        apkUrl = "https://github.com/zFitness/gow3_android/releases/download/v2.1.6-android3/God.of.War.III-2.1.6-android3.apk",
    )

    @Test
    fun `tag and version name parse to the same value`() {
        val fromTag = AndroidReleaseVersion.parse("v2.1.6-android3")
        val fromVersionName = AndroidReleaseVersion.parse("2.1.6-android3")
        assertEquals(fromTag, fromVersionName)
        assertEquals(listOf(2L, 1L, 6L), fromTag?.upstream)
        assertEquals(3L, fromTag?.androidRevision)
    }

    @Test
    fun `android revision compares numerically`() {
        val nine = AndroidReleaseVersion.parse("2.1.6-android9")
        val ten = AndroidReleaseVersion.parse("2.1.6-android10")
        assertTrue(nine!! < ten!!)
    }

    @Test
    fun `bionic tag and version name parse to the same value`() {
        val fromTag = AndroidReleaseVersion.parse("v2.1.7-bionic-v1")
        val fromVersionName = AndroidReleaseVersion.parse("2.1.7-bionic-v1")
        assertEquals(fromTag, fromVersionName)
        assertEquals(listOf(2L, 1L, 7L), fromTag?.upstream)
        assertEquals(1L, fromTag?.androidRevision)
    }

    @Test
    fun `bionic revision compares numerically`() {
        val one = AndroidReleaseVersion.parse("2.1.7-bionic-v1")
        val ten = AndroidReleaseVersion.parse("2.1.7-bionic-v10")
        assertTrue(one!! < ten!!)
    }

    @Test
    fun `installed bionic apk compares against a newer bionic release`() {
        val bionicRelease = release.copy(tag = "v2.2.0-bionic-v1")
        val result = UpdateChecker.compare("2.1.7-bionic-v1", "v2.2.0-bionic-v1", bionicRelease)
        assertTrue(result is UpdateCheckResult.Newer)
        assertEquals("2.2.0-bionic-v1", (result as UpdateCheckResult.Newer).version)
    }

    @Test
    fun `same upstream and revision across tag families report up to date`() {
        val result = UpdateChecker.compare("2.1.7-bionic-v1", "v2.1.7-android1", release)
        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `upstream segments compare numerically, not as strings`() {
        val older = AndroidReleaseVersion.parse("2.1.9-android1")
        val newer = AndroidReleaseVersion.parse("2.1.10-android1")
        assertTrue(older!! < newer!!)
    }

    @Test
    fun `newer upstream beats a higher android revision`() {
        val result = UpdateChecker.compare("2.1.6-android9", "v2.1.7-android1", release)
        assertTrue(result is UpdateCheckResult.Newer)
        assertEquals("2.1.7-android1", (result as UpdateCheckResult.Newer).version)
    }

    @Test
    fun `identical versions report up to date`() {
        val result = UpdateChecker.compare("2.1.6-android3", "v2.1.6-android3", release)
        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals("2.1.6-android3", (result as UpdateCheckResult.UpToDate).version)
    }

    @Test
    fun `upstream older than the installed apk reports up to date`() {
        val result = UpdateChecker.compare("2.1.7-android1", "v2.1.6-android3", release)
        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `debug placeholder version name is not comparable`() {
        assertNull(AndroidReleaseVersion.parse("0.5.2-kotlin"))
        val result = UpdateChecker.compare("0.5.2-kotlin", "v2.1.6-android3", release)
        assertTrue(result is UpdateCheckResult.NotComparable)
        assertEquals(NotComparableReason.INSTALLED_VERSION, (result as UpdateCheckResult.NotComparable).reason)
    }

    @Test
    fun `a latest tag that is not an android release is not comparable`() {
        val result = UpdateChecker.compare("2.1.6-android3", "v2.1.6-linux1", release)
        assertTrue(result is UpdateCheckResult.NotComparable)
        assertEquals(NotComparableReason.LATEST_TAG, (result as UpdateCheckResult.NotComparable).reason)
    }

    @Test
    fun `partial or decorated version strings are rejected`() {
        assertNull(AndroidReleaseVersion.parse("v2.1.6-android3-rc1"))
        assertNull(AndroidReleaseVersion.parse("2.1.6"))
        assertNull(AndroidReleaseVersion.parse("android3"))
        assertNull(AndroidReleaseVersion.parse(""))
        assertNull(AndroidReleaseVersion.parse(null))
    }

    @Test
    fun `a newer release carries the download page for the browser`() {
        val result = UpdateChecker.compare("2.1.6-android2", "v2.1.6-android3", release)
        assertTrue(result is UpdateCheckResult.Newer)
        val newer = result as UpdateCheckResult.Newer
        assertEquals(release.pageUrl, newer.release.pageUrl)
        assertTrue(newer.release.apkUrl!!.endsWith(".apk"))
    }
}
