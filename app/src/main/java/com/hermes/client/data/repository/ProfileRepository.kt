package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.ProfileDto

class ProfileRepository(private val rest: HermesRestApi) {
    suspend fun list(): List<ProfileDto> = rest.profiles()
}
