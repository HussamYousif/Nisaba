package com.nisaba.infra.inkfile

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

const val INK_FILE_VERSION = 1

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class InkFileDto(
    @ProtoNumber(1) val version: Int = INK_FILE_VERSION,
    @ProtoNumber(2) val strokes: List<StrokeDto> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class StrokeDto(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val origin: Int,
    @ProtoNumber(3) val exchangeId: String = "",
    @ProtoNumber(4) val color: Int,
    @ProtoNumber(5) val width: Float,
    @ProtoNumber(6) @ProtoPacked val xs: FloatArray,
    @ProtoNumber(7) @ProtoPacked val ys: FloatArray,
    @ProtoNumber(8) @ProtoPacked val ps: FloatArray,
    @ProtoNumber(9) @ProtoPacked val ts: IntArray,
)
