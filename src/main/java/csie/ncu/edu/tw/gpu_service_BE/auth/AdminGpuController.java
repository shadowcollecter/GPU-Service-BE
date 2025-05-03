package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/gpus")
@PreAuthorize("hasRole('ADMIN')")
public class AdminGpuController {
    @Autowired
    private GpuTypeRepository gpuTypeRepository;

    @GetMapping
    public ResponseEntity<List<GpuType>> listGpuTypes() {
        var list = gpuTypeRepository.findAll(Sort.by(Sort.Direction.ASC, "type"));
        return ResponseEntity.ok(list);
    }

    @PostMapping
    public ResponseEntity<List<GpuType>> addGpuType(@RequestBody GpuType gpuType) {
        gpuType.setEnabled(true);
        gpuTypeRepository.save(gpuType);
        var list = gpuTypeRepository.findAll(Sort.by(Sort.Direction.ASC, "type"));
        return ResponseEntity.ok(list);
    }

    @DeleteMapping("/{type}")
    public ResponseEntity<List<GpuType>> deleteGpuType(@PathVariable String type) {
        gpuTypeRepository.deleteById(type);
        var list = gpuTypeRepository.findAll(Sort.by(Sort.Direction.ASC, "type"));
        return ResponseEntity.ok(list);
    }

    @PatchMapping("/{type}")
    public ResponseEntity<List<GpuType>> updateGpuTypeEnabled(
            @PathVariable String type,
            @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().build();
        }
        return gpuTypeRepository.findById(type)
            .map(gpu -> {
                gpu.setEnabled(enabled);
                gpuTypeRepository.save(gpu);
                var list = gpuTypeRepository.findAll(Sort.by(Sort.Direction.ASC, "type"));
                return ResponseEntity.ok(list);
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}