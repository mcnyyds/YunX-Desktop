package com.yunx.app.data.network.model

class PlatformTaskPendingException(val taskId: String, message: String) : Exception(message)
