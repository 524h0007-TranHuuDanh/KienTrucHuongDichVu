package com.tdtu.ibanking.tuition.service;

import com.tdtu.ibanking.tuition.dto.TuitionDetailResponse;
import com.tdtu.ibanking.tuition.dto.TuitionResponse;
import com.tdtu.ibanking.tuition.entity.Student;
import com.tdtu.ibanking.tuition.entity.Tuition;
import com.tdtu.ibanking.tuition.exception.TuitionAlreadyPaidException;
import com.tdtu.ibanking.tuition.exception.TuitionNotFoundException;
import com.tdtu.ibanking.tuition.repository.StudentRepository;
import com.tdtu.ibanking.tuition.repository.TuitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TuitionService {

    private final StudentRepository studentRepository;
    private final TuitionRepository tuitionRepository;

    @Transactional(readOnly = true)
    public TuitionResponse getUnpaidByMssv(String mssv) {
        String normalized = normalize(mssv);

        Student student = studentRepository.findByMssv(normalized)
                .orElseThrow(() -> new TuitionNotFoundException(
                        "Không tìm thấy sinh viên với MSSV " + normalized));

        Tuition tuition = tuitionRepository.findFirstUnpaid(normalized)
                .orElseThrow(() -> new TuitionNotFoundException(
                        "Sinh viên " + normalized + " không còn khoản học phí chưa đóng"));

        return toResponse(tuition, student);
    }

    @Transactional(readOnly = true)
    public List<TuitionResponse> getAllByMssv(String mssv) {
        String normalized = normalize(mssv);

        Student student = studentRepository.findByMssv(normalized)
                .orElseThrow(() -> new TuitionNotFoundException(
                        "Không tìm thấy sinh viên với MSSV " + normalized));

        return tuitionRepository.findByStudentMssvOrderByDueDateAsc(normalized).stream()
                .map(t -> toResponse(t, student))
                .toList();
    }

    @Transactional(readOnly = true)
    public TuitionDetailResponse getById(UUID id) {
        Tuition tuition = tuitionRepository.findById(id)
                .orElseThrow(() -> new TuitionNotFoundException(id));
        return toDetail(tuition);
    }
    
    @Transactional
    public TuitionDetailResponse markPaid(UUID id, UUID transactionId) {
        Tuition tuition = tuitionRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new TuitionNotFoundException(id));

        if (Boolean.TRUE.equals(tuition.getPaid())) {
            if (transactionId.equals(tuition.getTransactionId())) {
                return toDetail(tuition);
            }
            throw new TuitionAlreadyPaidException(id);
        }

        tuition.setPaid(true);
        tuition.setPaidAt(LocalDateTime.now());
        tuition.setTransactionId(transactionId);
        return toDetail(tuitionRepository.save(tuition));
    }

    private String normalize(String mssv) {
        return mssv == null ? null : mssv.trim().toUpperCase();
    }

    private TuitionResponse toResponse(Tuition tuition, Student student) {
        TuitionResponse response = new TuitionResponse();
        response.setId(tuition.getId());
        response.setMssv(student.getMssv());
        response.setStudentName(student.getFullName());
        response.setSemester(tuition.getSemester());
        response.setAmount(tuition.getAmount());
        response.setPaid(tuition.getPaid());
        return response;
    }

    private TuitionDetailResponse toDetail(Tuition tuition) {
        Student student = tuition.getStudent();
        TuitionDetailResponse response = new TuitionDetailResponse();
        response.setId(tuition.getId());
        response.setMssv(student.getMssv());
        response.setStudentName(student.getFullName());
        response.setSemester(tuition.getSemester());
        response.setAmount(tuition.getAmount());
        response.setPaid(tuition.getPaid());
        response.setDueDate(tuition.getDueDate());
        response.setPaidAt(tuition.getPaidAt());
        response.setTransactionId(tuition.getTransactionId());
        return response;
    }
}
