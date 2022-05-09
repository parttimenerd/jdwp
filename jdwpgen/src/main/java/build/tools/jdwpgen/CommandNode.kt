/*
 * Copyright (c) 1998, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package build.tools.jdwpgen


// written in kotlin to be able to use KPoet

internal class CommandNode : AbstractCommandNode() {
    public override fun constrain(ctx: Context) {
        if (components.size == 4) {
            val out = components[0]
            val reply = components[1]
            val error = components[2]
            val onlyReads = components[3]
            if (out !is OutNode) {
                error("Expected 'Out' item, got: $out")
            }
            if (reply !is ReplyNode) {
                error("Expected 'Reply' item, got: $reply")
            }
            if (error !is ErrorSetNode) {
                error("Expected 'ErrorSet' item, got: $error")
            }
            if (onlyReads !is OnlyReadsNode) {
                error("Expected 'OnlyReads' item, got: $error")
            }
        } else if (components.size == 1) {
            val evt = components[0]
            if (evt !is EventNode) {
                error("Expected 'Event' item, got: $evt")
            }
        } else {
            error("Command $name must have Out and Reply items or ErrorSet item with OnlyReads item")
        }
        super.constrain(ctx)
    }

    val commandClassName: String
        get() = name()
    val requestClassName: String
        get() = commandClassName + "Request"
    val replyClassName: String
        get() = commandClassName + "Reply"

    val out: OutNode
        get() = components[0] as OutNode
    val reply: ReplyNode
        get() = components[1] as ReplyNode
    val error: ErrorSetNode
        get() = components[2] as ErrorSetNode
     val onlyReads: Boolean
        get() = (components[3] as OnlyReadsNode).boolean

    val eventNode: EventNode
        get() = components[0] as EventNode

    val isEventNode: Boolean
        get() = components[0] is EventNode
}