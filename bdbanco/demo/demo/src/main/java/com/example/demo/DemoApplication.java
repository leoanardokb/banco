package com.example.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.Data;

// ============================================================================
// SEÇÃO 0: CLASSE PRINCIPAL (SPRING BOOT RUNNER)
// ============================================================================

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}

// ============================================================================
// SEÇÃO 1: ENUMS E EXCEÇÕES DE NEGÓCIO
// ============================================================================

enum CategoriaBem { VEICULO, IMOVEL }
enum StatusFinanciamento { EM_ANALISE, APROVADO, REPROVADO, QUITADO }
enum StatusParcela { PENDENTE, PAGA, ATRASADA }

class RegraNegocioException extends RuntimeException {
    public RegraNegocioException(String message) { super(message); }
}

class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String message) { super(message); }
}

// ============================================================================
// SEÇÃO 2: ENTIDADES E DOMÍNIO (BANCO DE DADOS)
// ============================================================================

@Entity
@Data
class Cliente {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String cpf;
    
    @Column(nullable = false)
    private String nome;
    
    @Column(nullable = false)
    private BigDecimal renda;
    
    @Column(nullable = false)
    private LocalDate dataNascimento;
    
    private Integer scoreCredito; 
}

@Entity
@Data
class Bem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String tipo; 
    private String descricao;
    
    @Column(nullable = false)
    private BigDecimal valor;
    
    @Enumerated(EnumType.STRING)
    private CategoriaBem categoria; 
}

@Entity
@Data
class Financiamento {
    @Id
    private UUID numeroSolicitacao = UUID.randomUUID();
    
    @ManyToOne
    private Cliente cliente;
    
    @ManyToOne
    private Bem bem;
    
    private BigDecimal valorEntrada;
    private BigDecimal valorFinanciado;
    private Integer prazoMeses;
    private BigDecimal taxaJurosMensal;
    private BigDecimal valorParcela;
    
    @Enumerated(EnumType.STRING)
    private StatusFinanciamento status; 
    
    @OneToMany(mappedBy = "financiamento", cascade = CascadeType.ALL)
    private List<Parcela> parcelas = new ArrayList<>();
}

@Entity
@Data
class Parcela {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    private Financiamento financiamento;
    
    private Integer numero;
    private BigDecimal valorOriginal;
    private LocalDate dataVencimento;
    private LocalDate dataPagamento;
    
    @Enumerated(EnumType.STRING)
    private StatusParcela status; 
}

// ============================================================================
// SEÇÃO 3: REPOSITÓRIOS (SPRING DATA JPA)
// ============================================================================

interface ClienteRepository extends JpaRepository<Cliente, Long> {}
interface BemRepository extends JpaRepository<Bem, Long> {}
interface FinanciamentoRepository extends JpaRepository<Financiamento, UUID> {}
interface ParcelaRepository extends JpaRepository<Parcela, Long> {
    List<Parcela> findByFinanciamentoAndStatus(Financiamento financiamento, StatusParcela status);
}

// ============================================================================
// SEÇÃO 4: DTOs (TRANSFERÊNCIA DE DADOS)
// ============================================================================

@Data
@AllArgsConstructor
class SimulacaoResponse {
    private BigDecimal valorFinanciado;
    private BigDecimal valorParcela;
    private BigDecimal valorTotalPago;
    private Integer prazoMeses;
}

// ============================================================================
// SEÇÃO 5: SERVIÇOS (REGRAS DE NEGÓCIO)
// ============================================================================

@Service
class SimulacaoService {
    public SimulacaoResponse simular(BigDecimal valorBem, BigDecimal entrada, int prazo, BigDecimal taxaJuros) {
        BigDecimal valorFinanciado = valorBem.subtract(entrada);
        
        if (valorFinanciado.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RegraNegocioException("O valor financiado deve ser maior que zero.");
        }

        double i = taxaJuros.divide(BigDecimal.valueOf(100)).doubleValue();
        double pv = valorFinanciado.doubleValue();
        double pmt = (pv * i * Math.pow(1 + i, prazo)) / (Math.pow(1 + i, prazo) - 1);
        
        BigDecimal valorParcela = BigDecimal.valueOf(pmt).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPago = valorParcela.multiply(BigDecimal.valueOf(prazo));

        return new SimulacaoResponse(valorFinanciado, valorParcela, totalPago, prazo);
    }
}

@Service
class AprovacaoService {
    public boolean processarAprovacao(Financiamento solicitacao) {
        Cliente cliente = solicitacao.getCliente();
        BigDecimal valorBem = solicitacao.getBem().getValor();

        if (!validarIdade(cliente.getDataNascimento())) return false;
        if (!validarScore(cliente.getScoreCredito())) return false;
        if (!validarRenda(cliente.getRenda(), solicitacao.getValorParcela())) return false;
        if (!validarPercentualFinanciado(solicitacao.getValorFinanciado(), valorBem)) return false;

        return true;
    }

    private boolean validarIdade(LocalDate dataNascimento) {
        return Period.between(dataNascimento, LocalDate.now()).getYears() >= 18;
    }
    private boolean validarScore(Integer score) {
        return score != null && score >= 600;
    }
    private boolean validarRenda(BigDecimal renda, BigDecimal valorParcela) {
        return renda.compareTo(valorParcela.multiply(BigDecimal.valueOf(3))) >= 0; 
    }
    private boolean validarPercentualFinanciado(BigDecimal financiado, BigDecimal valorBem) {
        BigDecimal maxFinanciado = valorBem.multiply(BigDecimal.valueOf(0.90)); 
        return financiado.compareTo(maxFinanciado) <= 0;
    }
}

@Service
class PagamentoService {
    private static final BigDecimal MULTA_FIXA = new BigDecimal("0.02"); 
    private static final BigDecimal JUROS_MORA_DIARIO = new BigDecimal("0.00033"); 

    private final ParcelaRepository parcelaRepository;
    private final FinanciamentoRepository financiamentoRepository;

    public PagamentoService(ParcelaRepository parcelaRepository, FinanciamentoRepository financiamentoRepository) {
        this.parcelaRepository = parcelaRepository;
        this.financiamentoRepository = financiamentoRepository;
    }

    @Transactional
    public void registrarPagamento(Long idParcela, BigDecimal valorPago) {
        Parcela parcela = parcelaRepository.findById(idParcela)
            .orElseThrow(() -> new EntityNotFoundException("Parcela não encontrada"));

        if (parcela.getStatus() == StatusParcela.PAGA) {
            throw new RegraNegocioException("Parcela já encontra-se paga.");
        }

        BigDecimal valorExigido = calcularValorAtualizado(parcela);
        
        if (valorPago.compareTo(valorExigido) != 0) {
            throw new RegraNegocioException("Valor do pagamento diverge do valor atualizado da parcela.");
        }

        parcela.setDataPagamento(LocalDate.now());
        parcela.setStatus(StatusParcela.PAGA);
        parcelaRepository.save(parcela);
        
        verificarQuitacaoFinanciamento(parcela.getFinanciamento());
    }

    public BigDecimal calcularValorAtualizado(Parcela parcela) {
        if (parcela.getStatus() == StatusParcela.PAGA) {
            return parcela.getValorOriginal();
        }

        LocalDate hoje = LocalDate.now();
        if (hoje.isAfter(parcela.getDataVencimento())) {
            long diasAtraso = ChronoUnit.DAYS.between(parcela.getDataVencimento(), hoje);
            
            BigDecimal multa = parcela.getValorOriginal().multiply(MULTA_FIXA);
            BigDecimal juros = parcela.getValorOriginal()
                                      .multiply(JUROS_MORA_DIARIO)
                                      .multiply(BigDecimal.valueOf(diasAtraso));
            
            return parcela.getValorOriginal().add(multa).add(juros).setScale(2, RoundingMode.HALF_UP);
        }
        
        return parcela.getValorOriginal();
    }

    private void verificarQuitacaoFinanciamento(Financiamento financiamento) {
        List<Parcela> pendentes = parcelaRepository.findByFinanciamentoAndStatus(financiamento, StatusParcela.PENDENTE);
        List<Parcela> atrasadas = parcelaRepository.findByFinanciamentoAndStatus(financiamento, StatusParcela.ATRASADA);
        
        if (pendentes.isEmpty() && atrasadas.isEmpty()) {
            financiamento.setStatus(StatusFinanciamento.QUITADO);
            financiamentoRepository.save(financiamento);
        }
    }
}

// ============================================================================
// SEÇÃO 6: CONTROLLERS REST (ENDPOINTS DA API)
// ============================================================================

@RestController
@RequestMapping("/api/clientes")
class ClienteController {
    private final ClienteRepository clienteRepository;

    public ClienteController(ClienteRepository clienteRepository) {
        this.clienteRepository = clienteRepository;
    }

    @PostMapping
    public ResponseEntity<Cliente> cadastrar(@RequestBody Cliente cliente) {
        if (cliente.getScoreCredito() == null) {
            cliente.setScoreCredito(650); 
        }
        return ResponseEntity.ok(clienteRepository.save(cliente));
    }

    @GetMapping
    public List<Cliente> listarTodos() {
        return clienteRepository.findAll();
    }
}

@RestController
@RequestMapping("/api/bens")
class BemController {
    private final BemRepository bemRepository;

    public BemController(BemRepository bemRepository) {
        this.bemRepository = bemRepository;
    }

    @PostMapping
    public ResponseEntity<Bem> cadastrar(@RequestBody Bem bem) {
        if (bem.getValor() == null || bem.getValor().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RegraNegocioException("O valor do bem deve ser maior que zero.");
        }
        return ResponseEntity.ok(bemRepository.save(bem));
    }
}

@RestController
@RequestMapping("/api/simulacao")
class SimulacaoController {
    private final SimulacaoService simulacaoService;

    public SimulacaoController(SimulacaoService simulacaoService) {
        this.simulacaoService = simulacaoService;
    }

    @GetMapping
    public ResponseEntity<SimulacaoResponse> simular(
            @RequestParam BigDecimal valorBem,
            @RequestParam BigDecimal entrada,
            @RequestParam int prazo,
            @RequestParam BigDecimal taxaJuros) {
        
        return ResponseEntity.ok(simulacaoService.simular(valorBem, entrada, prazo, taxaJuros));
    }
}