package dev.proximity.core.ux

import dev.proximity.core.session.TokenResult
import dev.proximity.core.state.FailureReason

/** 안내 문구 (S9). 화면이 그대로 쓴다. 게임 용어를 쓰지 않는다 (I-2). */
object UxCopy {
    const val permissionRationale =
        "근처 기기를 찾고 연결하려면 '근처 기기' 권한이 필요합니다. 위치·계정 정보는 보내지 않고, 이 앱이 쓰는 임시 번호만 주고받습니다."

    fun nameTooLong(maxBytes: Int) = "닉네임이 너무 깁니다 (최대 ${maxBytes}바이트, 한글은 약 ${maxBytes / 3}자)."

    fun nameError(e: NameError, maxBytes: Int) = when (e) {
        NameError.Blank -> "닉네임을 입력하세요. 상대 기기의 확인 화면에 보입니다."
        NameError.TooLong -> nameTooLong(maxBytes)
    }

    fun radio(status: RadioStatus) = when (status) {
        RadioStatus.Ready -> ""
        RadioStatus.BluetoothOff -> "블루투스가 꺼져 있습니다. 켜면 바로 이어서 찾습니다."
        RadioStatus.PermissionNeeded -> "근처 기기 권한이 없어 찾을 수 없습니다. 세션 코드·QR로 들어갈 때도 이 권한이 필요합니다. 다시 요청할까요?"
        RadioStatus.PermissionDeniedForever -> "권한이 거부되어 다시 물을 수 없습니다. 설정 > 앱 > 권한에서 '근처 기기'를 허용하세요."
        RadioStatus.BackgroundRestricted -> "앱이 백그라운드에서 제한되어 연결이 끊길 수 있습니다. 화면을 켜 두거나 배터리 제한을 풀어 주세요."
        RadioStatus.Unsupported -> "이 기기는 필요한 블루투스 기능(BLE 광고)을 지원하지 않습니다."
    }

    fun failure(reason: FailureReason) = when (reason) {
        FailureReason.NoCandidate -> "부딪힌 기기를 찾지 못했습니다. 두 기기를 가까이 대고 다시 부딪히거나, 세션 코드·QR로 들어가세요."
        FailureReason.GameMismatch -> "상대 기기의 앱(게임)이 다르거나 버전이 맞지 않습니다."
        FailureReason.SessionConflict -> "두 기기가 서로 다른 세션에 있습니다. 한쪽이 나간 뒤 다시 부딪히세요."
        FailureReason.NoSessionToWatch -> "관전할 세션이 없습니다. 진행 중인 세션의 기기와 부딪히거나 플레이어로 바꾸세요."
        FailureReason.NoHostCapable -> "두 기기 모두 방을 열 수 없습니다. 한 명이 플레이어로 바꾸거나 '방 만들기'를 쓰세요."
        FailureReason.HostBidTie -> "호스트를 정하지 못했습니다. 다시 부딪히세요."
        FailureReason.SessionFull -> "세션에 자리가 없습니다. 관전자로 바꾸거나 다른 세션을 찾으세요."
        FailureReason.Rejected -> "상대가 거절했거나 응답하지 않았습니다. 다시 부딪히세요."
        FailureReason.Timeout -> "시간이 지나 취소되었습니다. 다시 시도하세요."
        FailureReason.Cancelled -> "취소했습니다."
        FailureReason.Internal -> "알 수 없는 문제가 생겼습니다. 다시 시도하세요."
    }

    fun refusal(code: Int) = when (code) {
        TokenResult.SESSION_FULL -> "세션이 가득 찼습니다."
        TokenResult.PLAYER_SLOTS_FULL -> "플레이어 자리가 없습니다. 관전자로는 들어갈 수 있습니다."
        TokenResult.SPECTATOR_SLOTS_FULL -> "관전자 자리가 없습니다."
        TokenResult.SPECTATORS_NOT_ALLOWED -> "이 세션은 관전을 받지 않습니다."
        TokenResult.GAME_MISMATCH -> "앱(게임) 버전이 맞지 않습니다. 업데이트하세요."
        TokenResult.GAME_REFUSED -> "호스트가 지금은 합류를 받지 않습니다."
        TokenResult.DISPLAY_NAME_TOO_LONG -> "닉네임이 너무 깁니다."
        TokenResult.BAD_TOKEN -> "합류 정보가 만료되었거나 이미 쓰였습니다. 새 QR을 찍으세요."
        TokenResult.BAD_CODE -> "세션 코드가 맞지 않습니다."
        TokenResult.CODE_DENIED -> "호스트가 합류를 수락하지 않았습니다."
        TokenResult.KICKED -> "호스트가 세션에서 내보냈습니다."
        TokenResult.UNREACHABLE -> "호스트에 연결하지 못했습니다. 가까이 가서 다시 시도하세요."
        else -> "합류하지 못했습니다."
    }
}
