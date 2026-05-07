package com.example.thesisproject.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import com.example.thesisproject.R
import com.example.thesisproject.model.CommuteConfig
import com.example.thesisproject.model.StopLineOption
import com.example.thesisproject.repository.CommuteConfigStore
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.LocalTime

class StopConfigBottomSheet : BottomSheetDialogFragment() {

    private lateinit var viewModel: StopConfigViewModel
    private lateinit var store: CommuteConfigStore

    private var lineOptions: List<StopLineOption> = emptyList()
    private var selectedOption: StopLineOption? = null
    private var startTime: LocalTime? = null
    private var endTime: LocalTime? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_commute_config, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[StopConfigViewModel::class.java]
        store = CommuteConfigStore(requireContext())

        val stopId = requireArguments().getString(ARG_STOP_ID)!!
        val stopName = requireArguments().getString(ARG_STOP_NAME) ?: ""

        view.findViewById<TextView>(R.id.stop_name).text = stopName

        val loading = view.findViewById<ProgressBar>(R.id.loading)
        val spinner = view.findViewById<Spinner>(R.id.line_spinner)
        val startBtn = view.findViewById<Button>(R.id.start_time_button)
        val endBtn = view.findViewById<Button>(R.id.end_time_button)
        val saveBtn = view.findViewById<Button>(R.id.save_button)
        val errorView = view.findViewById<TextView>(R.id.error_text)

        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            loading.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
        viewModel.lineOptions.observe(viewLifecycleOwner) { options ->
            lineOptions = options
            populateSpinner(spinner, options)
        }
        viewModel.loadLineOptions(stopId)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedOption = lineOptions.getOrNull(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {
                selectedOption = null
            }
        }

        startBtn.setOnClickListener {
            pickTime("Start time") { time ->
                startTime = time
                startBtn.text = "Start: ${time.format()}"
            }
        }
        endBtn.setOnClickListener {
            pickTime("End time") { time ->
                endTime = time
                endBtn.text = "End: ${time.format()}"
            }
        }

        saveBtn.setOnClickListener {
            errorView.visibility = View.GONE
            val option = selectedOption
            val start = startTime
            val end = endTime
            if (option == null) {
                showError(errorView, "Pick a line and direction")
                return@setOnClickListener
            }
            if (start == null || end == null) {
                showError(errorView, "Pick a start and end time")
                return@setOnClickListener
            }
            val config = CommuteConfig(
                stopId = stopId,
                lineId = option.line.id,
                direction = option.direction,
                timeWindowStart = start,
                timeWindowEnd = end,
                lineDesignation = option.line.name,
                transportMode = option.line.transportMode
            )
            if (!start.isBefore(end)) {
                showError(errorView, "End time must be after start time")
                return@setOnClickListener
            }
            if (store.overlapsAny(config)) {
                showError(errorView, "This time window overlaps another saved commute")
                return@setOnClickListener
            }
            if (store.add(config)) {
                Toast.makeText(requireContext(), "Commute saved", Toast.LENGTH_SHORT).show()
                // Tell the host activity a config was saved so it can redraw overlays.
                parentFragmentManager.setFragmentResult(RESULT_KEY_COMMUTE_SAVED, Bundle.EMPTY)
                dismiss()
            } else {
                showError(errorView, "Could not save — check times")
            }
        }
    }

    private fun populateSpinner(spinner: Spinner, options: List<StopLineOption>) {
        val labels = options.map { "${it.line.transportMode} ${it.line.name} → ${it.direction}" }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            if (labels.isEmpty()) listOf("No lines found for this stop") else labels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.isEnabled = options.isNotEmpty()
        if (options.isNotEmpty()) selectedOption = options[0]
    }

    private fun pickTime(title: String, onPicked: (LocalTime) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setTitleText(title)
            .build()
        picker.addOnPositiveButtonClickListener {
            onPicked(LocalTime.of(picker.hour, picker.minute))
        }
        picker.show(parentFragmentManager, "time_picker")
    }

    private fun showError(view: TextView, message: String) {
        view.text = message
        view.visibility = View.VISIBLE
    }

    private fun LocalTime.format(): String = "%02d:%02d".format(hour, minute)

    companion object {
        private const val ARG_STOP_ID = "stop_id"
        private const val ARG_STOP_NAME = "stop_name"

        /** Fragment-result key the host activity should listen to in order to know
         *  when a new commute has been saved (and the map overlays need to redraw). */
        const val RESULT_KEY_COMMUTE_SAVED = "commute_saved"

        fun newInstance(stopId: String, stopName: String): StopConfigBottomSheet {
            val sheet = StopConfigBottomSheet()
            sheet.arguments = Bundle().apply {
                putString(ARG_STOP_ID, stopId)
                putString(ARG_STOP_NAME, stopName)
            }
            return sheet
        }
    }
}
