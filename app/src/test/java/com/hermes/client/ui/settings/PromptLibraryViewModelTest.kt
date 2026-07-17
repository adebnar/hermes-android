package com.hermes.client.ui.settings

import com.hermes.client.data.repository.PromptStore
import com.hermes.client.data.repository.SavedPrompt
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PromptLibraryViewModelTest {
    private val store = mockk<PromptStore>(relaxed = true)
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()); every { store.prompts } returns MutableStateFlow(emptyList()) }
    @After fun tearDown() = Dispatchers.resetMain()
    private fun vm() = PromptLibraryViewModel(store)

    @Test fun save_new_generates_id_and_upserts() = runTest {
        vm().save(null, "T", "B"); advanceUntilIdle()
        coVerify { store.upsert(match { it.title == "T" && it.body == "B" && it.id.isNotBlank() }) }
    }

    @Test fun save_existing_keeps_id() = runTest {
        vm().save("keep-me", "T", "B"); advanceUntilIdle()
        coVerify { store.upsert(match { it.id == "keep-me" }) }
    }

    @Test fun blank_title_falls_back_to_first_body_line() = runTest {
        vm().save(null, "  ", "first line\nsecond"); advanceUntilIdle()
        coVerify { store.upsert(match { it.title == "first line" }) }
    }

    @Test fun delete_delegates() = runTest {
        vm().delete("x"); advanceUntilIdle()
        coVerify { store.delete("x") }
    }
}
