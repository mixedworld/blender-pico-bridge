package com.example.blenderpicobridge.p7f82b4c1.domain.usecase
import com.example.blenderpicobridge.p7f82b4c1.FakeBridgeRepository
import com.example.blenderpicobridge.p7f82b4c1.domain.model.Pairing
import org.junit.Assert.*
import org.junit.Test
class ConnectBridgeUseCaseTest {
    private val repository = FakeBridgeRepository()
    private val useCase = ConnectBridgeUseCase(repository)
    @Test fun normalizesPairingAndSavesOnlyValidatedInput() {
        useCase(Pairing(" http://192.168.1.2:8766/ ", " abc "))
        assertEquals(Pairing("http://192.168.1.2:8766", "abc"), repository.pairing)
    }
    @Test fun rejectsCredentialUrlsAndHeaderInjection() {
        listOf(Pairing("http://user:pass@192.168.1.2", "abc"), Pairing("http://192.168.1.2", "abc\nX: test"),
            Pairing("file:///private", "abc"), Pairing("http://192.168.1.2:0", "abc")).forEach {
            assertThrows(IllegalArgumentException::class.java) { useCase(it) }
        }
        assertEquals("test-token", repository.pairing.token)
    }
}
