/*
 * Copyright 2002-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("DEPRECATION")
package org.springframework.context.support

@Deprecated(message = "Use BeanRegistrarDsl instead")
fun beans(init: BeanDefinitionDsl.() -> Unit) = BeanDefinitionDsl(init)

/**
 * Class implementing functional bean definition Kotlin DSL.
 *
 * @constructor Create a new bean definition DSL.
 * @param condition the predicate to fulfill in order to take into account the inner
 * bean definition block
 * @author Sebastien Deleuze
 * @since 5.0
 */
@Deprecated(
	replaceWith = ReplaceWith("BeanRegistrarDsl", "org.springframework.beans.factory.BeanRegistrarDsl"),
	message = "Use BeanRegistrarDsl instead")
open class BeanDefinitionDsl internal constructor (private val init: BeanDefinitionDsl.() -> Unit,
							 private val condition: (ConfigurableEnvironment) -> Boolean = { true })
	: ApplicationContextInitializer<GenericApplicationContext> {

	@PublishedApi
	internal val children = arrayListOf<BeanDefinitionDsl>()

	/**
	 * @see BeanSupplierContext
	 */
	@PublishedApi
	internal lateinit var context: GenericApplicationContext
}
