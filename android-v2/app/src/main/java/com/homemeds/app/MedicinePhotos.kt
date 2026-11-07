package com.homemeds.app

const val MAX_MEDICINE_PHOTOS = 6

/** Shared by lookup and chat so a multi-angle batch is never silently truncated. */
internal fun medicineImages(single: String? = null, multiple: List<String> = emptyList()): List<String> {
    val images = (listOfNotNull(single) + multiple).filter(String::isNotBlank)
    if (images.size > MAX_MEDICINE_PHOTOS) throw UserError("同一药盒每次最多添加 $MAX_MEDICINE_PHOTOS 张照片")
    return images
}
