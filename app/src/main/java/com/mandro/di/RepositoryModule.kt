package com.mandro.di

import com.mandro.data.remote.EmgRepositoryImpl
import com.mandro.data.remote.UserRepositoryImpl
import com.mandro.data.remote.UsbRepositoryImpl
import com.mandro.domain.repository.EmgRepository
import com.mandro.domain.repository.UserRepository
import com.mandro.domain.repository.UsbRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindEmgRepository(impl: EmgRepositoryImpl): EmgRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds
    @Singleton
    abstract fun bindUsbRepository(impl: UsbRepositoryImpl): UsbRepository
}
