package com.mantra.player

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes

data class Mantra(
    val id: String,
    val title: String,
    val subtitle: String,
    @RawRes val rawRes: Int,
    @DrawableRes val imageRes: Int,
    /** Optional looping mp4 in res/raw, shown muted on the player screen instead of [imageRes]. */
    @RawRes val videoRes: Int? = null
) {
    companion object {
        val ALL = listOf(
            Mantra(
                "shivaya",
                "ॐ नमः शिवाय",
                "Om Namah Shivaya",
                R.raw.om_namah_shivaya,
                R.drawable.god_shiva
            ),
            Mantra(
                "gayatri",
                "गायत्री मंत्र",
                "Gayatri Mantra",
                R.raw.gayatri_mantra,
                R.drawable.god_gayatri
            ),
            Mantra(
                "ganpati",
                "ॐ गं गणपतये नम",
                "Om Gan Ganpataye Namo Namah",
                R.raw.om_gan_ganpataye_namo_namah,
                R.drawable.god_ganesha
            ),
            Mantra(
                "mrityunjaya",
                "महामृत्युंजय मंत्र",
                "Om Tryambakam Yajamahe",
                R.raw.om_tryambakam_yajamahe_sugandhim_pushtivardhanam,
                R.drawable.god_mrityunjaya
            ),
            Mantra(
                "santan_gopal",
                "संतान गोपाल मंत्र",
                "Santan Gopal Mantra",
                R.raw.santan_gopal_mantra,
                R.drawable.god_santan_gopal
            ),
            Mantra(
                "swami_samarth",
                "स्वामी समर्थ मंत्र",
                "Swami Samarth Mantra",
                R.raw.swami_samarth_mantra,
                R.drawable.god_swami_samarth
            ),
            Mantra(
                "hanuman_mantra",
                "हनुमान मंत्र",
                "Hanuman Mantra",
                R.raw.hanuman_mantra,
                R.drawable.god_hanuman_mantra
            ),
            Mantra(
                "sai",
                "साईं मंत्र",
                "Sai Baba Mantra",
                R.raw.sai_mantra,
                R.drawable.god_sai
            ),
            Mantra(
                "lakshmi_kuber",
                "लक्ष्मी कुबेर मंत्र",
                "Lakshmi Kuber Mantra",
                R.raw.lakshmi_kuber,
                R.drawable.god_lakshmi_kuber
            ),
            Mantra(
                "balaji",
                "बालाजी मंत्र",
                "Balaji Mantra",
                R.raw.balaji_mantra,
                R.drawable.god_balaji
            ),
            Mantra(
                "shani",
                "शनि मंत्र",
                "Shani Mantra",
                R.raw.shani_mantra,
                R.drawable.god_shani
            ),
            Mantra(
                "hanuman",
                "हनुमान चालीसा",
                "Hanuman Chalisa",
                R.raw.hanuman_chalisa,
                R.drawable.god_hanuman
            ),
            Mantra(
                "nishank",
                "निःशंक होई रे मना",
                "Nishank Hoi Re Mana",
                R.raw.nishank_hoi_re_mana,
                R.drawable.god_vitthal
            ),
            Mantra(
                "dattatreya",
                "श्री दत्तात्रेय कवच",
                "Shri Dattatreya Kavach",
                R.raw.shri_dattatreya_kavach,
                R.drawable.god_dattatreya
            )
        )

        fun byId(id: String?): Mantra? = ALL.firstOrNull { it.id == id }

        fun indexOf(id: String?): Int = ALL.indexOfFirst { it.id == id }

        fun previousOf(id: String?): Mantra {
            val index = indexOf(id)
            return if (index <= 0) ALL.last() else ALL[index - 1]
        }

        fun nextOf(id: String?): Mantra {
            val index = indexOf(id)
            return if (index < 0 || index >= ALL.lastIndex) ALL.first() else ALL[index + 1]
        }
    }
}
