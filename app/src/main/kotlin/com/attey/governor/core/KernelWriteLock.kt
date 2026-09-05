package com.attey.governor.core

import kotlinx.coroutines.sync.Mutex

/** Serializes multi-node changes so two profiles cannot interleave into a hybrid. */
internal val kernelWriteMutex = Mutex()
